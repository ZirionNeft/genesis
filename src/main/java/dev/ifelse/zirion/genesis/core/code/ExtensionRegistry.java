package dev.ifelse.zirion.genesis.core.code;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Frozen trusted definitions; validates before dependent operations without rewriting stored bytes. */
public final class ExtensionRegistry {
    /** Validators must be pure/bounded, without IO or blocking. Live plugin scheduling is not implemented. */
    public record Support(ExtensionPayload.Key key, ExtensionPayload.Kind kind, Predicate<ImmutableBytes> canonical) {
        public Support {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(canonical, "canonical validator");
        }
    }

    public enum Reason { MISSING_REQUIRED_DATA, MISSING_EXTENSION, VERSION_UNSUPPORTED, SCHEMA_UNSUPPORTED,
        KIND_MISMATCH, INVALID_PAYLOAD, VALIDATOR_FAILED }
    public record Issue(ContentId extensionId, Reason reason) {}
    public sealed interface Result permits Accepted, Rejected {}
    public record Accepted() implements Result {}
    public record Rejected(Issue issue) implements Result {}

    private final Map<ExtensionPayload.Key, Support> supports;

    private ExtensionRegistry(Map<ExtensionPayload.Key, Support> supports) { this.supports = Map.copyOf(supports); }

    public static Builder builder() { return new Builder(); }

    /** Missing optional cosmetics also reject by default. No fallback based solely on untrusted flags. */
    public Result check(GenesisBlueprint blueprint, Set<ExtensionPayload.Key> required) {
        Objects.requireNonNull(blueprint, "blueprint");
        for (ExtensionPayload.Key dependency : required.stream()
                .sorted(java.util.Comparator.comparing(key -> key.extension().id())).toList()) {
            boolean present = blueprint.extensions().stream().anyMatch(data -> data.key().equals(dependency)
                    && data.kind() == ExtensionPayload.Kind.REQUIRED);
            if (!present) return rejected(dependency.extension().id(), Reason.MISSING_REQUIRED_DATA);
        }
        // Resolve every dependency's metadata before invoking any payload validator.
        for (ExtensionPayload data : blueprint.extensions()) {
            Support support = supports.get(data.key());
            if (support == null) {
                ContentId id = data.key().extension().id();
                boolean known = supports.keySet().stream().anyMatch(key -> key.extension().id().equals(id));
                boolean versionKnown = supports.keySet().stream().anyMatch(key -> key.extension().equals(data.key().extension()));
                return rejected(id, !known ? Reason.MISSING_EXTENSION : !versionKnown ? Reason.VERSION_UNSUPPORTED : Reason.SCHEMA_UNSUPPORTED);
            }
            if (support.kind() != data.kind()) return rejected(data.key().extension().id(), Reason.KIND_MISMATCH);
        }
        for (ExtensionPayload data : blueprint.extensions()) {
            try {
                if (!supports.get(data.key()).canonical().test(data.payload())) return rejected(data.key().extension().id(), Reason.INVALID_PAYLOAD);
            } catch (RuntimeException exception) {
                return rejected(data.key().extension().id(), Reason.VALIDATOR_FAILED);
            }
        }
        return new Accepted();
    }

    private static Rejected rejected(ContentId id, Reason reason) { return new Rejected(new Issue(id, reason)); }

    public static final class Builder {
        private final Map<ExtensionPayload.Key, Support> supports = new HashMap<>();
        private ExtensionRegistry frozen;

        /** Namespace is supplied by the trusted loader, not authenticated by this internal builder. */
        public Builder register(String registrarNamespace, Support support) {
            if (frozen != null) throw new IllegalStateException("Extension registry is frozen");
            Objects.requireNonNull(support, "support");
            if (!support.key().extension().id().namespace().equals(registrarNamespace)) throw new IllegalArgumentException("Registrar namespace mismatch");
            if (supports.putIfAbsent(support.key(), support) != null) throw new IllegalArgumentException("Duplicate extension version/schema");
            return this;
        }

        public ExtensionRegistry freeze() {
            if (frozen == null) frozen = new ExtensionRegistry(supports);
            return frozen;
        }
    }
}
