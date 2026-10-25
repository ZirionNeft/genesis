package dev.ifelse.zirion.genesis.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

import static dev.ifelse.zirion.genesis.config.CatalogDocument.*;
import static org.junit.jupiter.api.Assertions.*;

class CatalogLoaderTest {
    @Test
    void shippedBundleLoadsAllFamilySchemas() {
        var bundle = new CatalogLoader().loadBundled(getClass().getClassLoader());
        assertEquals(4, bundle.families().size());
        assertEquals(6, bundle.documents().size());
        for (var entry : Map.of("canids", 26, "runners", 27, "heavies", 27, "avians", 28).entrySet()) {
            assertEquals(entry.getValue(), bundle.families().get(
                    dev.ifelse.zirion.genesis.core.genetics.ContentId.parse("genesis:" + entry.getKey())).genes().size());
        }
    }

    @Test
    void missingHornFamilyRejectsEvenWithMatchingFingerprint() throws Exception {
        var json = CatalogFixtures.coherent();
        var families = new JsonArray(); families.add("genesis:heavies");
        json.get(EXPRESSION).getAsJsonObject("genes").getAsJsonObject("HORN_FORM").add("families", families);
        CatalogFixtures.refresh(json);
        var failure = assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(json)));
        assertEquals("FAMILY_GENE_MEMBERSHIP", failure.code());
        assertEquals(EXPRESSION.filename() + "#/genes/HORN_FORM/families", failure.path());
    }

    @Test
    void directoryLoadingDoesNotRewriteDesignFiles() throws Exception {
        var before = CatalogFixtures.originals();
        assertEquals(4, new CatalogLoader().loadDirectory(Path.of(System.getProperty("genesis.gddDirectory"))).families().size());
        var after = CatalogFixtures.originals();
        for (CatalogDocument file : values()) assertArrayEquals(before.get(file), after.get(file));
    }

    @Test
    void sixPackagedDocumentsExactlyMatchSourceBytes() throws Exception {
        var expected = CatalogFixtures.originals();
        try (var jar = new JarFile(System.getProperty("genesis.pluginJar"))) {
            for (CatalogDocument file : values()) {
                var entry = jar.getJarEntry(file.resourcePath()); assertNotNull(entry, file.resourcePath());
                try (var source = jar.getInputStream(entry)) { assertArrayEquals(expected.get(file), source.readAllBytes()); }
            }
        }
    }

    @Test
    void allExistingFingerprintsMatchTheLimitedSourceProfile() throws Exception {
        // Expected digests come from the supplied source, not a rehash helper.
        var originals = CatalogFixtures.originals();
        var cfg = new JsonNode(StrictJson.parse(originals.get(CONFIG), CONFIG.filename()), "config#");
        for (var entry : Map.of("geneSchema", SCHEMA, "hybridGraph", GRAPH, "expressionRules", EXPRESSION, "morphologySet", MORPHOLOGY).entrySet()) {
            var document = new JsonNode(StrictJson.parse(originals.get(entry.getValue()), entry.getValue().filename()), "test#");
            assertEquals(cfg.field("genetics").field("registries").field(entry.getKey()).field("sha256").string(), DesignFingerprint.sha256(document));
        }
        var expression = new JsonNode(StrictJson.parse(originals.get(EXPRESSION), "expression"), "e#");
        assertEquals(expression.field("catalogCompatibility").field("contentCatalogSha256").string(),
                DesignFingerprint.sha256(new JsonNode(StrictJson.parse(originals.get(CONTENT), "content"), "c#")));
    }

    @Test
    void coherentFixtureLoadsAllFamilySchemasWithoutMutableJsonEscaping() throws Exception {
        var json = CatalogFixtures.coherent(); var input = CatalogFixtures.bytes(json);
        var bundle = new CatalogLoader().load(input);
        assertEquals(4, bundle.families().size()); assertEquals(6, bundle.documents().size());
        assertEquals(26, bundle.families().get(dev.ifelse.zirion.genesis.core.genetics.ContentId.parse("genesis:canids")).genes().size());
        byte[] expected = bundle.documents().get(SCHEMA.filename()).copy();
        input.get(SCHEMA)[0] = 0;
        assertArrayEquals(expected, bundle.documents().get(SCHEMA.filename()).copy());
        assertThrows(UnsupportedOperationException.class, () -> bundle.families().clear());
        assertThrows(UnsupportedOperationException.class, () -> bundle.documents().clear());
    }

    @Test
    void corruptedFingerprintFailsEvenIfTheShapeIsValid() throws Exception {
        var json = CatalogFixtures.coherent(); json.get(SCHEMA).addProperty("serialization", "changed");
        var failure = assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(json)));
        assertEquals("FINGERPRINT", failure.code());
    }

    @Test
    void unknownRequiredRegistryVersionIsRejected() throws Exception {
        var json = CatalogFixtures.coherent(); json.get(SCHEMA).addProperty("version", 1); CatalogFixtures.refresh(json);
        assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(json)));
    }

    @Test
    void graphCannotOptIntoImplicitExtensionEdges() throws Exception {
        var json = CatalogFixtures.coherent();
        json.get(GRAPH).addProperty("extensionPolicy", "infer-from-roots");
        CatalogFixtures.refresh(json);
        assertEquals("REFERENCE", assertThrows(ConfigurationException.class,
                () -> new CatalogLoader().load(CatalogFixtures.bytes(json))).code());
    }

    @Test
    void expressionCannotSilentlyChangeTheFormulaOrInnateSlotContract() throws Exception {
        var formula = CatalogFixtures.coherent();
        formula.get(EXPRESSION).getAsJsonObject("statPolicy").addProperty("formula", "multiply-all-percent-effects");
        CatalogFixtures.refresh(formula);
        assertEquals("REFERENCE", assertThrows(ConfigurationException.class,
                () -> new CatalogLoader().load(CatalogFixtures.bytes(formula))).code());
        var ability = CatalogFixtures.coherent();
        ability.get(EXPRESSION).getAsJsonObject("abilityPolicy").addProperty("innateMaxActive", 2);
        CatalogFixtures.refresh(ability);
        assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(ability)));
    }

    @Test
    void missingSymbolicVisualAndUtilityStatesCannotFallBackAtRuntime() throws Exception {
        var visual = CatalogFixtures.coherent();
        visual.get(EXPRESSION).getAsJsonObject("genes").getAsJsonObject("BODY_FRAME").getAsJsonObject("pairRules")
                .getAsJsonObject("normal|normal").getAsJsonObject("visual").remove("body");
        CatalogFixtures.refresh(visual);
        assertEquals("EXPRESSION", assertThrows(ConfigurationException.class,
                () -> new CatalogLoader().load(CatalogFixtures.bytes(visual))).code());
        var utility = CatalogFixtures.coherent();
        utility.get(EXPRESSION).getAsJsonObject("genes").getAsJsonObject("METABOLISM").getAsJsonObject("pairRules")
                .getAsJsonObject("normal|normal").getAsJsonObject("utility").remove("feedConsumptionScale");
        CatalogFixtures.refresh(utility);
        assertEquals("EXPRESSION", assertThrows(ConfigurationException.class,
                () -> new CatalogLoader().load(CatalogFixtures.bytes(utility))).code());
    }

    @Test
    void ambiguousUtilityCompositionAndUnknownMagicStatesAreRejected() throws Exception {
        var utility = CatalogFixtures.coherent();
        for (var pair : utility.get(EXPRESSION).getAsJsonObject("genes").getAsJsonObject("MUSCLE").getAsJsonObject("pairRules").entrySet()) {
            pair.getValue().getAsJsonObject().getAsJsonObject("utility").addProperty("feedConsumptionScale", "1.00");
        }
        CatalogFixtures.refresh(utility);
        assertEquals("EXPRESSION", assertThrows(ConfigurationException.class,
                () -> new CatalogLoader().load(CatalogFixtures.bytes(utility))).code());
        var arcana = CatalogFixtures.coherent();
        arcana.get(EXPRESSION).getAsJsonObject("genes").getAsJsonObject("ARCANA").getAsJsonObject("pairRules")
                .getAsJsonObject("quiet|quiet").addProperty("state", "unsupported");
        CatalogFixtures.refresh(arcana);
        assertEquals("EXPRESSION", assertThrows(ConfigurationException.class,
                () -> new CatalogLoader().load(CatalogFixtures.bytes(arcana))).code());
    }

    @Test
    void unsupportedMutationSelectionOrGuaranteeConditionsCannotBeIgnored() throws Exception {
        var selection = CatalogFixtures.coherent();
        selection.get(CONTENT).getAsJsonObject("mutationVariantSelection").addProperty("method", "choose-gene-first");
        CatalogFixtures.refresh(selection);
        assertEquals("REFERENCE", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(selection))).code());
        var guarantee = CatalogFixtures.coherent();
        guarantee.get(CONTENT).getAsJsonObject("mutationRules").getAsJsonObject("guarantee").addProperty("tier", "base");
        CatalogFixtures.refresh(guarantee);
        assertEquals("REFERENCE", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(guarantee))).code());
    }

    @Test
    void duplicateMutationTargetsAndMislabelledMagicAreRejectedWithValidHashes() throws Exception {
        var duplicate = CatalogFixtures.coherent();
        var definitions = duplicate.get(CONTENT).getAsJsonArray("mutations");
        var a = definitions.get(0).getAsJsonObject(); var b = definitions.get(1).getAsJsonObject();
        b.addProperty("geneId", a.get("geneId").getAsString()); b.addProperty("resultingVariant", a.get("resultingVariant").getAsString());
        CatalogFixtures.refresh(duplicate);
        assertEquals("MUTATION_RULES", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(duplicate))).code());
        var magic = CatalogFixtures.coherent();
        magic.get(CONTENT).getAsJsonArray("mutations").get(0).getAsJsonObject().addProperty("category", "magical");
        CatalogFixtures.refresh(magic);
        assertEquals("MUTATION_RULES", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(magic))).code());
    }

    @Test
    void malformedMutationIdsHaveStructuredDiagnosticsAndLoadedDefinitionsAreImmutable() throws Exception {
        var invalid = CatalogFixtures.coherent(); invalid.get(CONTENT).getAsJsonArray("mutations").get(0).getAsJsonObject().addProperty("id", "bad:token");
        CatalogFixtures.refresh(invalid);
        assertEquals("MUTATION_ID", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(invalid))).code());
        var bundle = new CatalogLoader().loadBundled(getClass().getClassLoader());
        assertEquals(21, bundle.mutations().definitions().size());
        assertThrows(UnsupportedOperationException.class, () -> bundle.mutations().definitions().clear());
    }

    @Test
    void graphCoverageAndHistoricalUnionAreValidatedAfterHashVerification() throws Exception {
        var json = CatalogFixtures.coherent();
        json.get(GRAPH).getAsJsonArray("families").get(0).getAsJsonObject().getAsJsonArray("allowedPairs").remove(0);
        CatalogFixtures.refresh(json);
        assertEquals("PAIR_COVERAGE", assertThrows(ConfigurationException.class,
                () -> new CatalogLoader().load(CatalogFixtures.bytes(json))).code());
        var wrongChild = CatalogFixtures.coherent();
        wrongChild.get(GRAPH).getAsJsonArray("families").get(0).getAsJsonObject().getAsJsonArray("allowedPairs")
                .get(0).getAsJsonObject().addProperty("childForm", "genesis:wolf_fox");
        CatalogFixtures.refresh(wrongChild);
        assertEquals("CHILD_ROOTS", assertThrows(ConfigurationException.class,
                () -> new CatalogLoader().load(CatalogFixtures.bytes(wrongChild))).code());
    }

    @Test
    void reversedDuplicatePairAndCrossFamilyReferenceAreRejected() throws Exception {
        var json = CatalogFixtures.coherent();
        var pairs = json.get(GRAPH).getAsJsonArray("families").get(0).getAsJsonObject().getAsJsonArray("allowedPairs");
        JsonObject reverse = pairs.get(1).deepCopy().getAsJsonObject();
        String a = reverse.get("parentA").getAsString(); reverse.addProperty("parentA", reverse.get("parentB").getAsString()); reverse.addProperty("parentB", a);
        pairs.add(reverse); CatalogFixtures.refresh(json);
        assertEquals("DUPLICATE", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(json))).code());
        var foreign = CatalogFixtures.coherent();
        foreign.get(GRAPH).getAsJsonArray("families").get(0).getAsJsonObject().getAsJsonArray("allowedPairs")
                .get(0).getAsJsonObject().addProperty("parentB", "genesis:horse"); CatalogFixtures.refresh(foreign);
        assertEquals("PAIR_REFERENCE", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(foreign))).code());
    }

    @Test
    void incompletePairRulesAndUnknownVisualSlotsAreRejected() throws Exception {
        var json = CatalogFixtures.coherent(); json.get(EXPRESSION).getAsJsonObject("genes").getAsJsonObject("BODY_FRAME").getAsJsonObject("pairRules").remove("large|normal");
        CatalogFixtures.refresh(json);
        assertEquals("PAIR_COVERAGE", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(json))).code());
        var slot = CatalogFixtures.coherent();
        slot.get(EXPRESSION).getAsJsonObject("genes").getAsJsonObject("BODY_FRAME").getAsJsonObject("pairRules")
                .getAsJsonObject("normal|normal").getAsJsonObject("visual").addProperty("unregistered", "normal");
        CatalogFixtures.refresh(slot);
        assertEquals("VISUAL_SLOT", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(slot))).code());
    }

    @Test
    void schemaContentAndMutationTargetsAreCrossChecked() throws Exception {
        var json = CatalogFixtures.coherent(); json.get(CONTENT).getAsJsonArray("mutations").get(0).getAsJsonObject().addProperty("geneId", "HERITAGE_1");
        CatalogFixtures.refresh(json);
        assertEquals("MUTATION_TARGET", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(json))).code());
        var wrongCount = CatalogFixtures.coherent(); wrongCount.get(SCHEMA).getAsJsonObject("geneCountByFamily").addProperty("genesis:canids", 12);
        CatalogFixtures.refresh(wrongCount);
        assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(wrongCount)));
    }

    @Test
    void defaultConfigMirrorsAndRoleReferencesCannotDrift() throws Exception {
        var json = CatalogFixtures.coherent(); json.get(CONTENT).getAsJsonObject("lifeSparks").addProperty("initialCradleCapacity", 8);
        CatalogFixtures.refresh(json);
        assertEquals("CONFIG_MIRROR", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(json))).code());
        var badRole = CatalogFixtures.coherent(); var roles = new JsonArray(); roles.add("unknown");
        badRole.get(CONTENT).getAsJsonArray("forms").get(0).getAsJsonObject().add("allowedRoles", roles); CatalogFixtures.refresh(badRole);
        assertEquals("ROLE", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(CatalogFixtures.bytes(badRole))).code());
    }

    @Test
    void missingDocumentsAndDuplicateJsonNeverProducePartialBundles() throws Exception {
        var input = new EnumMap<>(CatalogFixtures.originals()); input.remove(CONTENT);
        assertEquals("INPUT", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(input)).code());
        var duplicate = CatalogFixtures.originals(); duplicate.put(SCHEMA, "{\"id\":1,\"id\":2}".getBytes(StandardCharsets.UTF_8));
        assertEquals("DUPLICATE", assertThrows(ConfigurationException.class, () -> new CatalogLoader().load(duplicate)).code());
    }

    @Test
    void unsupportedFingerprintProfilesFailInsteadOfClaimingGeneralJcs() {
        for (String source : List.of("{\"a\":1e-5}", "{\"ключ\":1}", "{\"a\":0.123456789012345678901}")) {
            var node = new JsonNode(StrictJson.parse(source.getBytes(StandardCharsets.UTF_8), "profile"), "profile#");
            assertEquals("FINGERPRINT_PROFILE", assertThrows(ConfigurationException.class, () -> DesignFingerprint.sha256(node)).code());
        }
    }
}
