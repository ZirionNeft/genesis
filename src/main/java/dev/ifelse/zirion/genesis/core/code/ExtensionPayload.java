package dev.ifelse.zirion.genesis.core.code;

import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;

import java.util.Objects;

/** Payload remains opaque until a matching, trusted definition validates its canonical form. */
public record ExtensionPayload(Key key, Kind kind, ImmutableBytes payload) {
    public static final int MAX_BYTES = 16_384;
    public enum Kind { REQUIRED, OPTIONAL_COSMETIC }

    public record Key(RulesetVersion extension, int schemaVersion) {
        public Key {
            Objects.requireNonNull(extension, "extension");
            if (schemaVersion < 1) throw new IllegalArgumentException("Extension schema version must be positive");
        }
    }

    public ExtensionPayload {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(payload, "payload");
        if (payload.size() > MAX_BYTES) throw new IllegalArgumentException("Extension payload exceeds limit");
    }
}
