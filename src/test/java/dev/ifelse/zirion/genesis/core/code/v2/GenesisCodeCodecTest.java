package dev.ifelse.zirion.genesis.core.code.v2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ifelse.zirion.genesis.config.CatalogLoader;
import dev.ifelse.zirion.genesis.core.code.ExtensionPayload;
import dev.ifelse.zirion.genesis.core.code.ImmutableBytes;
import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.phenotype.v2.PhenotypeResolver;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

class GenesisCodeCodecTest {
    private static final CatalogBundle BUNDLE = new CatalogLoader().loadBundled(GenesisCodeCodecTest.class.getClassLoader());
    private static final ServerConfig.CodeLimits LIMITS = BUNDLE.defaults().genesisCode().limits();
    private static final GenesisCodeCodec CODEC = new GenesisCodeCodec(LIMITS);
    private static final GeneticBlueprintResolver RESOLVER = new GeneticBlueprintResolver(BUNDLE);
    private static final JsonObject VECTORS = JsonParser.parseReader(new InputStreamReader(
            GenesisCodeCodecTest.class.getResourceAsStream("/genesis-code-v2-vectors.json"), StandardCharsets.UTF_8)).getAsJsonObject();

    @Test
    void independentPythonVectorsPinAll28FormsFullGenomesCanonicalBytesAndWireChecksums() {
        assertEquals(31, VECTORS.getAsJsonArray("cases").size());
        for (var entry : VECTORS.getAsJsonArray("cases")) {
            var row = entry.getAsJsonObject(); String code = row.get("code").getAsString();
            byte[] canonical = row.get("canonical").getAsString().getBytes(StandardCharsets.UTF_8);
            var blueprint = CODEC.decode(code);
            assertArrayEquals(canonical, CODEC.encodePayload(blueprint)); assertEquals(code, CODEC.encode(blueprint));
            assertEquals(blueprint, CODEC.decodePayload(canonical));
            var source = row.getAsJsonObject("payload");
            assertEquals(source.get("historicalForm").getAsString(), blueprint.historicalForm().toString());
            assertEquals(Long.parseLong(source.get("visualSeed").getAsString()), blueprint.visualSeed());
            assertEquals(source.getAsJsonObject("genes").size(), blueprint.genome().genes().size());
            source.getAsJsonObject("genes").entrySet().forEach(pair -> {
                var decoded = blueprint.genome().genes().get(ContentId.parse(pair.getKey())); var expected = pair.getValue().getAsJsonArray();
                assertEquals(expected.get(0).getAsString(), decoded.first().toString()); assertEquals(expected.get(1).getAsString(), decoded.second().toString());
            });
            if (blueprint.extensions().isEmpty()) {
                assertEquals(BUNDLE.expressionRules().references(), blueprint.references());
                assertEquals(new PhenotypeResolver(BUNDLE).resolve(blueprint.form(), blueprint.genome()), RESOLVER.resolve(blueprint));
            } else assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(blueprint));
        }
    }

    @Test
    void jcsUtf16SortingControlEscapesAndUnicodePreservationMatchIndependentVectors() {
        for (var entry : VECTORS.getAsJsonArray("jcs")) {
            var row = entry.getAsJsonObject(); byte[] input = row.get("value").toString().getBytes(StandardCharsets.UTF_8);
            assertEquals(row.get("canonical").getAsString(), new String(PayloadJson.write(PayloadJson.read(input), 8192), StandardCharsets.UTF_8));
        }
        String supplementaryFirst = new String(PayloadJson.write(Map.of("\ue000", "b", "\ud800\udc00", "a"), 100), StandardCharsets.UTF_8);
        assertTrue(supplementaryFirst.indexOf("\ud800\udc00") < supplementaryFirst.indexOf("\ue000"));
        assertThrows(IllegalArgumentException.class, () -> PayloadJson.write(Map.of("bad", "\ud800"), 100));
        assertThrows(IllegalArgumentException.class, () -> PayloadJson.write(Map.of("float", 1.0), 100));
    }

    @Test
    void opaqueExtensionsIncludingEmptyAndNonUtf8BytesSurviveWithoutClaimingSupport() {
        for (int index = 28; index < 31; index++) {
            var row = vector(index); var blueprint = CODEC.decode(row.get("code").getAsString());
            var source = row.getAsJsonObject("payload").getAsJsonArray("extensions").get(0).getAsJsonObject();
            assertArrayEquals(Base64.getUrlDecoder().decode(source.get("payload").getAsString()), blueprint.extensions().getFirst().payload().copy());
            assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(blueprint));
            byte[] copy = blueprint.extensions().getFirst().payload().copy();
            if (copy.length > 0) { copy[0] ^= 1; assertNotEquals(new ImmutableBytes(copy), blueprint.extensions().getFirst().payload()); }
        }
    }

    @Test
    void allFourRegistryAndContentPinsRequireExactDefinitionsEvenWithValidCrc() {
        for (String registry : List.of("geneSchema", "hybridGraph", "expressionRules", "morphologySet")) {
            for (String field : List.of("id", "sha256")) {
                var payload = payload(0);
                object(object(payload.get("registries")).get(registry)).put(field, field.equals("id") ? "example:unavailable" : "0".repeat(64));
                var unresolved = CODEC.decode(wire(PayloadJson.write(payload, LIMITS.maxPayloadBytes())));
                assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(unresolved));
                assertEquals(unresolved, CODEC.decode(CODEC.encode(unresolved)));
            }
        }
        for (String field : List.of("id", "sha256")) {
            var payload = payload(0); object(payload.get("contentCatalog")).put(field, field.equals("id") ? "example:catalog" : "0".repeat(64));
            var unresolved = CODEC.decode(wire(PayloadJson.write(payload, LIMITS.maxPayloadBytes())));
            assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(unresolved));
        }
    }

    @Test
    void structuralDecodeCannotBypassWholeGenomeFamilyOrHistoricalRootsValidation() {
        for (Consumer<Map<String, Object>> edit : List.<Consumer<Map<String, Object>>>of(
                value -> object(value.get("genes")).remove("genesis:element"),
                value -> object(value.get("genes")).put("genesis:element", List.of("genesis:unknown", "genesis:unknown")),
                value -> value.put("historicalForm", "genesis:fox"),
                value -> value.put("familyId", "genesis:avians"))) {
            var payload = payload(0); edit.accept(payload);
            var blueprint = CODEC.decode(wire(PayloadJson.write(payload, LIMITS.maxPayloadBytes())));
            assertThrows(IllegalArgumentException.class, () -> RESOLVER.resolve(blueprint));
        }
    }

    @Test
    void unsupportedPayloadAndGeneticVersionsUnknownFieldsAndWrongTypesReject() {
        var edits = List.<Consumer<Map<String, Object>>>of(
                value -> value.put("payloadVersion", 1), value -> value.put("owner", "forged"),
                value -> value.remove("historicalForm"), value -> value.put("visualSeed", 42),
                value -> object(object(value.get("registries")).get("geneSchema")).put("version", 1),
                value -> object(object(value.get("registries")).get("geneSchema")).put("version", 3),
                value -> object(value.get("contentCatalog")).put("version", 1),
                value -> value.put("genes", List.of()), value -> value.put("extensions", Map.of()),
                value -> object(value.get("genes")).put("genesis:element", List.of("genesis:stone")));
        for (var edit : edits) assertBadPayload(0, edit);
    }

    @Test
    void seedIsLosslessSigned64BitStringAndAlternateOrOverflowRepresentationsReject() {
        for (String seed : List.of("-9223372036854775808", "9223372036854775807", "0", "-1", "9007199254740993")) {
            var payload = payload(0); payload.put("visualSeed", seed);
            assertEquals(Long.parseLong(seed), CODEC.decode(wire(PayloadJson.write(payload, LIMITS.maxPayloadBytes()))).visualSeed());
        }
        for (String seed : List.of("+1", "01", "-0", "-01", "1e3", "1.0", "9223372036854775808", "-9223372036854775809", "")) {
            assertBadPayload(0, value -> value.put("visualSeed", seed));
        }
    }

    @Test
    void noncanonicalJsonOrderWhitespaceEscapesAndAlleleOrExtensionOrderReject() {
        String canonical = vector(0).get("canonical").getAsString();
        for (String bad : List.of(" " + canonical, canonical + "\n", canonical.replaceFirst("\\{", "{\n"),
                canonical.replace("genesis:wolf", "genesis:\\u0077olf"), canonical.replace("\"payloadVersion\":2", "\"payloadVersion\":2.0"))) {
            assertNotEquals(canonical, bad); assertThrows(IllegalArgumentException.class, () -> CODEC.decode(wire(bad.getBytes(StandardCharsets.UTF_8))));
        }
        // Reversed keys are valid JSON but not canonical wire bytes.
        String keyOrder = "{\"visualSeed\":\"0\"," + canonical.substring(1, canonical.lastIndexOf(",\"visualSeed\"")) + "}";
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(wire(keyOrder.getBytes(StandardCharsets.UTF_8))));
        var payload = payload(1); var genes = object(payload.get("genes"));
        var pair = genes.values().stream().map(GenesisCodeCodecTest::array).filter(p -> !p.get(0).equals(p.get(1))).findFirst().orElseThrow();
        Collections.reverse(pair);
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(wire(PayloadJson.write(payload, LIMITS.maxPayloadBytes()))));
        var blueprint = blueprint(0); var a = extension("a:x", 1); var b = extension("a.b:x", 1);
        var withExtensions = new GenesisBlueprint(blueprint.references(), blueprint.historicalForm(), blueprint.genome(), 0, List.of(b, a));
        var sorted = CODEC.decode(CODEC.encode(withExtensions)); assertEquals(a, sorted.extensions().getFirst());
        var reversed = object(PayloadJson.read(CODEC.encodePayload(sorted))); Collections.reverse(array(reversed.get("extensions")));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodePayload(PayloadJson.write(reversed, LIMITS.maxPayloadBytes())));
    }

    @Test
    void malformedJsonDuplicateKeysSurrogatesInvalidUtf8AndDeepContainersReject() {
        for (String bad : List.of("", "{}{}", "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "{\"a\":null}",
                "{\"a\":true}", "{\"a\":1e2}", "{\"a\":01}", "{\"a\":2147483648}", "{\"a\":\"\\ud800\"}", "{\"a\":\"\\udc00\"}",
                "{\"a\":\"\\u00xz\"}", "{\"a\":\"\\q\"}", "{\"a\":\"\n\"}", "[".repeat(20) + "0" + "]".repeat(20), "\ufeff{}")) {
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodePayload(bad.getBytes(StandardCharsets.UTF_8)), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> CODEC.decodePayload(new byte[]{'{', '"', 'a', '"', ':', '"', (byte) 0xc0, (byte) 0x80, '"', '}'}));
        byte[] valid = CODEC.encodePayload(blueprint(0));
        for (int cut = 0; cut < valid.length; cut++) {
            byte[] truncated = java.util.Arrays.copyOf(valid, cut);
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodePayload(truncated));
        }
    }

    @Test
    void invalidTransportPaddingTrailingBitsCrcAndOldPrototypeNeverPassAsNewGen1() {
        String valid = CODEC.encode(blueprint(0)); int dot = valid.lastIndexOf('.');
        for (String bad : List.of("GEN1-old-prototype", valid.toLowerCase(java.util.Locale.ROOT), valid + ".x", valid.replace("GEN1.", "GEN2."),
                valid.substring(0, dot) + "=." + valid.substring(dot + 1), valid.substring(0, dot + 1) + "00000000", " " + valid, valid + "\n",
                "GEN1..12345678", "GEN1.A.12345678", "GEN1.+/.12345678")) {
            assertThrows(IllegalArgumentException.class, () -> CODEC.decode(bad));
        }
        String emptyObject = wire("{}".getBytes(StandardCharsets.UTF_8));
        assertTrue(emptyObject.startsWith("GEN1.e30."));
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(emptyObject.replace("e30", "e31")));
        for (String bad : List.of("AR", "AQ=", "+/", "A")) assertBadPayload(28, value -> object(array(value.get("extensions")).getFirst()).put("payload", bad));
    }

    @Test
    void configuredByteCharacterGeneAndExtensionLimitsAreEnforcedAtBoundaries() {
        var blueprint = blueprint(0); int bytes = CODEC.encodePayload(blueprint).length, chars = CODEC.encode(blueprint).length(), genes = blueprint.genome().genes().size();
        var exact = new GenesisCodeCodec(new ServerConfig.CodeLimits(chars, bytes, genes, 0));
        assertEquals(blueprint, exact.decode(exact.encode(blueprint)));
        for (var limits : List.of(new ServerConfig.CodeLimits(chars - 1, bytes, genes, 0),
                new ServerConfig.CodeLimits(chars, bytes - 1, genes, 0), new ServerConfig.CodeLimits(chars, bytes, genes - 1, 0))) {
            var small = new GenesisCodeCodec(limits);
            assertThrows(IllegalArgumentException.class, () -> small.encode(blueprint));
            assertThrows(IllegalArgumentException.class, () -> small.decode(CODEC.encode(blueprint)));
        }
        var noExtensions = new GenesisCodeCodec(new ServerConfig.CodeLimits(LIMITS.maxCodeChars(), LIMITS.maxPayloadBytes(), LIMITS.maxGenes(), 0));
        assertThrows(IllegalArgumentException.class, () -> noExtensions.decode(vector(28).get("code").getAsString()));
        assertThrows(IllegalArgumentException.class, () -> noExtensions.encode(blueprint(28)));
        assertEquals(4, PayloadJson.write("é", 4).length);
        assertThrows(IllegalArgumentException.class, () -> PayloadJson.write("é", 3));
        var oversized = new GenesisBlueprint.OpaqueExtension(extension("example:large", 1).definition(), 1,
                ExtensionPayload.Kind.REQUIRED, new ImmutableBytes(new byte[LIMITS.maxPayloadBytes() + 1]));
        var large = new GenesisBlueprint(blueprint.references(), blueprint.historicalForm(), blueprint.genome(), 0, List.of(oversized));
        assertThrows(IllegalArgumentException.class, () -> CODEC.encode(large));
    }

    @Test
    void extensionMetadataDuplicatesAndOpaqueCollectionAliasingAreChecked() {
        for (Consumer<Map<String, Object>> edit : List.<Consumer<Map<String, Object>>>of(
                value -> object(array(value.get("extensions")).getFirst()).put("kind", "harmless"),
                value -> object(array(value.get("extensions")).getFirst()).put("schemaVersion", 0),
                value -> object(object(array(value.get("extensions")).getFirst()).get("definition")).put("sha256", "INVALID"),
                value -> array(value.get("extensions")).add(array(value.get("extensions")).getFirst()))) assertBadPayload(28, edit);
        var base = blueprint(0); var original = extension("example:x", 1); var list = new ArrayList<>(List.of(original));
        var immutable = new GenesisBlueprint(base.references(), base.historicalForm(), base.genome(), 0, list); list.clear();
        assertEquals(List.of(original), immutable.extensions());
        assertThrows(UnsupportedOperationException.class, () -> immutable.extensions().clear());
        assertThrows(IllegalArgumentException.class, () -> new GenesisBlueprint(base.references(), base.historicalForm(), base.genome(), 0, List.of(original, extension("example:x", 2))));
    }

    @Test
    void boundedRandomMalformedInputsFailWithControlledErrorsAndParallelRoundtripsAreStable() {
        var random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            byte[] bytes = new byte[random.nextInt(512)]; random.nextBytes(bytes);
            assertThrows(IllegalArgumentException.class, () -> CODEC.decodePayload(bytes));
        }
        var count = new AtomicInteger();
        java.util.stream.IntStream.range(0, 100).parallel().forEach(i -> {
            String wire = vector(i % 31).get("code").getAsString(); assertEquals(wire, CODEC.encode(CODEC.decode(wire))); count.incrementAndGet();
        });
        assertEquals(100, count.get());
    }

    private static JsonObject vector(int index) { return VECTORS.getAsJsonArray("cases").get(index).getAsJsonObject(); }
    private static GenesisBlueprint blueprint(int index) { return CODEC.decode(vector(index).get("code").getAsString()); }
    private static Map<String, Object> payload(int index) { return object(PayloadJson.read(vector(index).get("canonical").getAsString().getBytes(StandardCharsets.UTF_8))); }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
    @SuppressWarnings("unchecked") private static List<Object> array(Object value) { return (List<Object>) value; }
    private static void assertBadPayload(int vector, Consumer<Map<String, Object>> edit) {
        var payload = payload(vector); edit.accept(payload);
        assertThrows(IllegalArgumentException.class, () -> CODEC.decode(wire(PayloadJson.write(payload, LIMITS.maxPayloadBytes()))));
    }
    private static String wire(byte[] payload) {
        var crc = new CRC32(); crc.update(payload);
        return "GEN1." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload) + "." + String.format(java.util.Locale.ROOT, "%08X", crc.getValue());
    }
    private static GenesisBlueprint.OpaqueExtension extension(String id, int version) {
        return new GenesisBlueprint.OpaqueExtension(new RegistryFingerprint(new RulesetVersion(ContentId.parse(id), version), "0".repeat(64)),
                1, ExtensionPayload.Kind.OPTIONAL_COSMETIC, new ImmutableBytes(new byte[]{0, (byte) 0xff}));
    }
}
