package dev.ifelse.zirion.genesis.core.code;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.ifelse.zirion.genesis.core.code.CodeFixtures.*;
import static dev.ifelse.zirion.genesis.core.code.ExtensionRegistry.Reason.*;
import static org.junit.jupiter.api.Assertions.*;

class ExtensionRegistryTest {
    private static final ExtensionPayload DATA = vector().extensions().getFirst();

    private static ExtensionRegistry supported() {
        return ExtensionRegistry.builder().register("v", new ExtensionRegistry.Support(DATA.key(), DATA.kind(), DATA.payload()::equals)).freeze();
    }

    private static void rejected(ExtensionRegistry registry, GenesisBlueprint blueprint, ExtensionRegistry.Reason reason) {
        var result = assertInstanceOf(ExtensionRegistry.Rejected.class, registry.check(blueprint, Set.of()));
        assertEquals(reason, result.issue().reason());
    }

    @Test
    void absentExtensionRemainsReadableAndReturnsAfterMatchingDefinitionsAreRestored() {
        String stored = GenesisCodeCodec.encode(vector());
        var opaque = GenesisCodeCodec.decode(stored);
        rejected(ExtensionRegistry.builder().freeze(), opaque, MISSING_EXTENSION);
        assertEquals(stored, GenesisCodeCodec.encode(opaque));
        assertInstanceOf(ExtensionRegistry.Accepted.class, supported().check(opaque, Set.of(DATA.key())));
        assertEquals(stored, GenesisCodeCodec.encode(opaque));
    }

    @Test
    void unsupportedVersionsSchemasAndKindsHaveSeparateReasons() {
        var otherVersion = new ExtensionPayload.Key(ref("v:x", 1), 3);
        var old = ExtensionRegistry.builder().register("v", new ExtensionRegistry.Support(otherVersion, DATA.kind(), bytes -> true)).freeze();
        rejected(old, vector(), VERSION_UNSUPPORTED);
        var otherSchema = new ExtensionPayload.Key(ref("v:x", 2), 2);
        var schema = ExtensionRegistry.builder().register("v", new ExtensionRegistry.Support(otherSchema, DATA.kind(), bytes -> true)).freeze();
        rejected(schema, vector(), SCHEMA_UNSUPPORTED);
        var wrongKind = ExtensionRegistry.builder().register("v", new ExtensionRegistry.Support(DATA.key(), ExtensionPayload.Kind.OPTIONAL_COSMETIC, bytes -> true)).freeze();
        rejected(wrongKind, vector(), KIND_MISMATCH);
    }

    @Test
    void authoritativeRequiredDependenciesCannotBeDroppedOrDowngraded() {
        var registry = supported();
        var missing = withExtensions(vector(), List.of());
        var result = assertInstanceOf(ExtensionRegistry.Rejected.class, registry.check(missing, Set.of(DATA.key())));
        assertEquals(MISSING_REQUIRED_DATA, result.issue().reason());
        var downgraded = new ExtensionPayload(DATA.key(), ExtensionPayload.Kind.OPTIONAL_COSMETIC, DATA.payload());
        var altered = withExtensions(vector(), List.of(downgraded));
        assertEquals(MISSING_REQUIRED_DATA, assertInstanceOf(ExtensionRegistry.Rejected.class,
                registry.check(altered, Set.of(DATA.key()))).issue().reason());
        rejected(registry, altered, KIND_MISMATCH);
    }

    @Test
    void anUnknownCosmeticFlagAloneDoesNotAuthorizeFallback() {
        var cosmetic = new ExtensionPayload(DATA.key(), ExtensionPayload.Kind.OPTIONAL_COSMETIC, DATA.payload());
        rejected(ExtensionRegistry.builder().freeze(), withExtensions(vector(), List.of(cosmetic)), MISSING_EXTENSION);
    }

    @Test
    void missingMetadataRejectsBeforeAnyPayloadValidatorRuns() {
        AtomicInteger calls = new AtomicInteger();
        var first = extension("a:known", 1, 1, ExtensionPayload.Kind.REQUIRED, new byte[]{1});
        var registry = ExtensionRegistry.builder().register("a", new ExtensionRegistry.Support(first.key(), first.kind(), bytes -> {
            calls.incrementAndGet(); return true;
        })).freeze();
        rejected(registry, withExtensions(vector(), List.of(first, DATA)), MISSING_EXTENSION);
        assertEquals(0, calls.get());
    }

    @Test
    void noncanonicalPayloadAndValidatorFailureRejectWithoutRewritingBytes() {
        var invalid = new ExtensionPayload(DATA.key(), DATA.kind(), new ImmutableBytes(new byte[]{1, 2, 3}));
        var blueprint = withExtensions(vector(), List.of(invalid));
        String before = GenesisCodeCodec.encode(blueprint);
        rejected(supported(), blueprint, INVALID_PAYLOAD);
        var failing = ExtensionRegistry.builder().register("v", new ExtensionRegistry.Support(DATA.key(), DATA.kind(), bytes -> {
            throw new IllegalStateException("fixture failure");
        })).freeze();
        rejected(failing, blueprint, VALIDATOR_FAILED);
        assertEquals(before, GenesisCodeCodec.encode(blueprint));
    }

    @Test
    void trustedScopeDuplicatesAndFreezePreventDefinitionReplacement() {
        var support = new ExtensionRegistry.Support(DATA.key(), DATA.kind(), DATA.payload()::equals);
        var builder = ExtensionRegistry.builder();
        assertThrows(IllegalArgumentException.class, () -> builder.register("foreign", support));
        builder.register("v", support);
        assertThrows(IllegalArgumentException.class, () -> builder.register("v", support));
        var frozen = builder.freeze();
        assertSame(frozen, builder.freeze());
        assertThrows(IllegalStateException.class, () -> builder.register("v", support));
        assertInstanceOf(ExtensionRegistry.Accepted.class, frozen.check(vector(), Set.of()));
    }
}
