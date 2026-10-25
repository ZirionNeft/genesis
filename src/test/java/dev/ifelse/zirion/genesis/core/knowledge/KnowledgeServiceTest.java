package dev.ifelse.zirion.genesis.core.knowledge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ifelse.zirion.genesis.config.CatalogLoader;
import dev.ifelse.zirion.genesis.config.ConfigurationRuntime;
import dev.ifelse.zirion.genesis.core.code.ExtensionPayload;
import dev.ifelse.zirion.genesis.core.code.ImmutableBytes;
import dev.ifelse.zirion.genesis.core.code.v2.GenesisBlueprint;
import dev.ifelse.zirion.genesis.core.code.v2.GenesisCodeCodec;
import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import static dev.ifelse.zirion.genesis.core.knowledge.DisclosureAccess.Permission.*;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeServiceTest {
    private static final CatalogBundle BUNDLE = new CatalogLoader().loadBundled(KnowledgeServiceTest.class.getClassLoader());
    private static final ServerConfig.Code POLICY = BUNDLE.defaults().genesisCode();
    private static final GenesisCodeCodec CODEC = new GenesisCodeCodec(POLICY.limits());
    private static final JsonObject VECTORS = JsonParser.parseReader(new InputStreamReader(
            KnowledgeServiceTest.class.getResourceAsStream("/genesis-code-v2-vectors.json"), StandardCharsets.UTF_8)).getAsJsonObject();
    private static final KnowledgeService SERVICE = new ConfigurationRuntime(BUNDLE, BUNDLE.defaults()).snapshot().knowledgeService();
    private static final GenesisBlueprint WOLF = CODEC.decode(VECTORS.getAsJsonArray("cases").get(0).getAsJsonObject().get("code").getAsString());
    private static final UUID VIEWER = new UUID(0, 1);
    private static final KnowledgeSubject SUBJECT = new KnowledgeSubject(KnowledgeSubject.Kind.CREATURE, new UUID(0, 2));
    private static final DisclosureAccess ACCESS = new DisclosureAccess(VIEWER, SUBJECT, 0, EnumSet.allOf(DisclosureAccess.Permission.class));

    @Test
    void unknownIndividualStartsWithNoFactsAndNoFullCodeUnderDefaultPolicy() {
        var unknown = SERVICE.unknown(VIEWER, SUBJECT, WOLF);
        assertTrue(unknown.observedVisualSlots().isEmpty()); assertTrue(unknown.analyzedGenes().isEmpty()); assertEquals(0, unknown.revision());
        assertTrue(SERVICE.passport(WOLF, unknown, ACCESS).observedVisualTraits().isEmpty());
        assertFalse(SERVICE.analysis(WOLF, unknown, ACCESS).complete()); assertTrue(SERVICE.analysis(WOLF, unknown, ACCESS).knownGenes().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> SERVICE.fullCode(WOLF, unknown, ACCESS));
        assertThrows(IllegalArgumentException.class, () -> SERVICE.exportOwnCode(WOLF, unknown, ACCESS));
    }

    @Test
    void observationAndGeneticAnalysisAreIndependentEvenForAHiddenCarrier() {
        var carrier = pair(WOLF, "pattern", "plain", "spotted");
        var unknown = SERVICE.unknown(VIEWER, SUBJECT, carrier);
        var observed = SERVICE.recordObservation(carrier, unknown, ACCESS, Set.of("pattern")).next();
        assertEquals(Map.of("pattern", "plain"), SERVICE.passport(carrier, observed, ACCESS).observedVisualTraits());
        assertTrue(SERVICE.analysis(carrier, observed, ACCESS).knownGenes().isEmpty());
        var analyzed = SERVICE.recordAnalysis(carrier, unknown, ACCESS, Set.of(id("pattern"))).next();
        assertTrue(SERVICE.passport(carrier, analyzed, ACCESS).observedVisualTraits().isEmpty());
        assertEquals(Map.of(id("pattern"), new AllelePair(id("plain"), id("spotted"))), SERVICE.analysis(carrier, analyzed, ACCESS).knownGenes());
        assertFalse(SERVICE.analysis(carrier, analyzed, ACCESS).complete());
    }

    @Test
    void publicPassportIsIdenticalForVisuallyIndistinguishableGenotypesEvenAfterFullAnalysis() {
        for (var hidden : List.of(new String[]{"pattern", "plain", "spotted", "plain", "plain"},
                new String[]{"eye_appearance", "luminous", "ordinary", "ordinary", "ordinary"},
                new String[]{"resource_trait", "gifted", "normal", "normal", "normal"},
                new String[]{"healing", "normal", "quick", "normal", "normal"})) {
            var a = pair(WOLF, hidden[0], hidden[1], hidden[2]); var b = pair(WOLF, hidden[0], hidden[3], hidden[4]);
            var visuals = new dev.ifelse.zirion.genesis.core.code.v2.GeneticBlueprintResolver(BUNDLE).resolve(a).visualStates().keySet();
            var ka = SERVICE.recordObservation(a, SERVICE.unknown(VIEWER, SUBJECT, a), ACCESS, visuals).next();
            var kb = SERVICE.recordObservation(b, SERVICE.unknown(VIEWER, SUBJECT, b), ACCESS, visuals).next();
            assertNotEquals(ka.blueprintSha256(), kb.blueprintSha256());
            assertEquals(SERVICE.passport(a, ka, ACCESS), SERVICE.passport(b, kb, ACCESS));
            ka = SERVICE.recordAnalysis(a, ka, ACCESS, a.genome().genes().keySet()).next();
            kb = SERVICE.recordAnalysis(b, kb, ACCESS, b.genome().genes().keySet()).next();
            assertEquals(SERVICE.passport(a, ka, ACCESS), SERVICE.passport(b, kb, ACCESS));
            assertNotEquals(SERVICE.analysis(a, ka, ACCESS).knownGenes(), SERVICE.analysis(b, kb, ACCESS).knownGenes());
        }
    }

    @Test
    void publicDtoAndSerializedResponseHaveAnAllowlistWithNoNestedHiddenPayload() {
        var known = SERVICE.recordObservation(WOLF, SERVICE.unknown(VIEWER, SUBJECT, WOLF), ACCESS, Set.of("pattern")).next();
        known = SERVICE.recordAnalysis(WOLF, known, ACCESS, WOLF.genome().genes().keySet()).next();
        var passport = SERVICE.passport(WOLF, known, ACCESS);
        var fields = Arrays.stream(PublicPassport.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
        assertEquals(Set.of("subject", "observedVisualTraits"), fields);
        var json = new Gson().toJsonTree(passport).getAsJsonObject();
        assertEquals(Set.of("subject", "observedVisualTraits"), json.keySet());
        assertEquals(Set.of("pattern"), json.getAsJsonObject("observedVisualTraits").keySet());
        assertEquals("plain", json.getAsJsonObject("observedVisualTraits").get("pattern").getAsString());
        String serialized = json.toString();
        for (String forbidden : List.of("genesis:", "GEN1.", "blueprint", "visualSeed", "knownGenes", "analyzedGenes", "potential", "heritage", "sha256", "revision")) {
            assertFalse(serialized.contains(forbidden), forbidden);
        }
    }

    @Test
    void onlyCompleteIndividualAnalysisUnlocksGen1AndObservationCannotSubstituteForIt() {
        var unknown = SERVICE.unknown(VIEWER, SUBJECT, WOLF);
        var allSlots = new dev.ifelse.zirion.genesis.core.code.v2.GeneticBlueprintResolver(BUNDLE).resolve(WOLF).visualStates().keySet();
        var observed = SERVICE.recordObservation(WOLF, unknown, ACCESS, allSlots).next();
        assertThrows(IllegalArgumentException.class, () -> SERVICE.fullCode(WOLF, observed, ACCESS));
        var missing = new HashSet<>(WOLF.genome().genes().keySet()); missing.remove(id("heritage_3"));
        var partial = SERVICE.recordAnalysis(WOLF, observed, ACCESS, missing).next();
        assertThrows(IllegalArgumentException.class, () -> SERVICE.fullCode(WOLF, partial, ACCESS));
        var complete = SERVICE.recordAnalysis(WOLF, partial, ACCESS, Set.of(id("heritage_3"))).next();
        assertTrue(SERVICE.analysis(WOLF, complete, ACCESS).complete());
        assertEquals(WOLF, CODEC.decode(SERVICE.fullCode(WOLF, complete, ACCESS)));
        assertEquals(CODEC.encode(WOLF), SERVICE.exportOwnCode(WOLF, complete, ACCESS));
        assertTrue(unknown.analyzedGenes().isEmpty());
    }

    @Test
    void alwaysVisibilityChangesReadEligibilityButDoesNotGrantFactsOrPermissions() {
        var always = new KnowledgeService(BUNDLE, policy(ServerConfig.Visibility.ALWAYS, true, true));
        var unknown = always.unknown(VIEWER, SUBJECT, WOLF);
        assertEquals(CODEC.encode(WOLF), always.fullCode(WOLF, unknown, ACCESS));
        assertFalse(always.analysis(WOLF, unknown, ACCESS).complete());
        assertTrue(always.analysis(WOLF, unknown, ACCESS).knownGenes().isEmpty());
        var onlyObserve = access(Set.of(READ_PUBLIC_PASSPORT));
        assertThrows(IllegalArgumentException.class, () -> always.fullCode(WOLF, unknown, onlyObserve));
        assertThrows(IllegalArgumentException.class, () -> always.exportOwnCode(WOLF, unknown, onlyObserve));
    }

    @Test
    void publicFlagOwnerExportFlagAndReadPermissionsAreSeparate() {
        var privateOnly = new KnowledgeService(BUNDLE, policy(ServerConfig.Visibility.ALWAYS, false, false));
        var unknown = privateOnly.unknown(VIEWER, SUBJECT, WOLF);
        assertThrows(IllegalArgumentException.class, () -> privateOnly.passport(WOLF, unknown, ACCESS));
        assertThrows(IllegalArgumentException.class, () -> privateOnly.exportOwnCode(WOLF, unknown, ACCESS));
        assertEquals(CODEC.encode(WOLF), privateOnly.fullCode(WOLF, unknown, ACCESS));
        assertNotNull(privateOnly.analysis(WOLF, unknown, ACCESS));
        var enabled = new KnowledgeService(BUNDLE, policy(ServerConfig.Visibility.ALWAYS, true, true));
        assertThrows(IllegalArgumentException.class, () -> enabled.exportOwnCode(WOLF, unknown, access(Set.of(READ_FULL_CODE))));
        assertThrows(IllegalArgumentException.class, () -> enabled.exportOwnCode(WOLF, unknown, access(Set.of(EXPORT_OWN_CODE))));
        assertEquals(CODEC.encode(WOLF), enabled.exportOwnCode(WOLF, unknown, access(Set.of(READ_FULL_CODE, EXPORT_OWN_CODE))));
    }

    @Test
    void allOperationsRequireTheirOwnServerCapability() {
        var unknown = SERVICE.unknown(VIEWER, SUBJECT, WOLF); var denied = access(Set.of());
        assertThrows(IllegalArgumentException.class, () -> SERVICE.passport(WOLF, unknown, denied));
        assertThrows(IllegalArgumentException.class, () -> SERVICE.analysis(WOLF, unknown, denied));
        assertThrows(IllegalArgumentException.class, () -> SERVICE.recordObservation(WOLF, unknown, denied, Set.of("pattern")));
        assertThrows(IllegalArgumentException.class, () -> SERVICE.recordAnalysis(WOLF, unknown, denied, Set.of(id("pattern"))));
        assertThrows(IllegalArgumentException.class, () -> SERVICE.recordAnalysis(WOLF, unknown, access(Set.of(READ_ANALYSIS)), Set.of(id("pattern"))));
        assertThrows(IllegalArgumentException.class, () -> SERVICE.recordObservation(WOLF, unknown, access(Set.of(READ_PUBLIC_PASSPORT)), Set.of("pattern")));
    }

    @Test
    void knowledgeIsScopedToViewerSubjectKindAndIndividualNotFamilyOrIdenticalGenome() {
        var complete = complete(WOLF);
        for (var wrong : List.of(new DisclosureAccess(new UUID(0, 77), SUBJECT, 1, ACCESS.permissions()),
                new DisclosureAccess(VIEWER, new KnowledgeSubject(SUBJECT.kind(), new UUID(0, 77)), 1, ACCESS.permissions()),
                new DisclosureAccess(VIEWER, new KnowledgeSubject(KnowledgeSubject.Kind.WILD_CANDIDATE, SUBJECT.id()), 1, ACCESS.permissions()),
                new DisclosureAccess(VIEWER, new KnowledgeSubject(KnowledgeSubject.Kind.SPARK, SUBJECT.id()), 1, ACCESS.permissions()))) {
            assertThrows(IllegalArgumentException.class, () -> SERVICE.passport(WOLF, complete, wrong));
            assertThrows(IllegalArgumentException.class, () -> SERVICE.analysis(WOLF, complete, wrong));
            assertThrows(IllegalArgumentException.class, () -> SERVICE.fullCode(WOLF, complete, wrong));
            assertThrows(IllegalArgumentException.class, () -> SERVICE.recordObservation(WOLF, complete, wrong, Set.of("pattern")));
            assertThrows(IllegalArgumentException.class, () -> SERVICE.recordAnalysis(WOLF, complete, wrong, Set.of(id("pattern"))));
        }
        var otherSubject = new KnowledgeSubject(SUBJECT.kind(), new UUID(0, 88));
        var other = SERVICE.unknown(VIEWER, otherSubject, WOLF);
        var otherAccess = new DisclosureAccess(VIEWER, otherSubject, 0, ACCESS.permissions());
        assertEquals(complete.blueprintSha256(), other.blueprintSha256());
        assertThrows(IllegalArgumentException.class, () -> SERVICE.fullCode(WOLF, other, otherAccess));
    }

    @Test
    void changedGenomeOrVisualSeedCannotReuseAnOldAnalysisAndUnknownPinsFailClosed() {
        var complete = complete(WOLF);
        var changed = pair(WOLF, "pattern", "plain", "spotted");
        var changedSeed = new GenesisBlueprint(WOLF.references(), WOLF.historicalForm(), WOLF.genome(), WOLF.visualSeed() + 1, List.of());
        for (var other : List.of(changed, changedSeed)) {
            assertThrows(IllegalArgumentException.class, () -> SERVICE.passport(other, complete, ACCESS));
            assertThrows(IllegalArgumentException.class, () -> SERVICE.fullCode(other, complete, ACCESS));
        }
        var raw = JsonParser.parseString(new String(CODEC.encodePayload(WOLF), StandardCharsets.UTF_8)).getAsJsonObject();
        raw.getAsJsonObject("registries").getAsJsonObject("morphologySet").addProperty("sha256", "0".repeat(64));
        // Gson ordering matches these already sorted fixture keys; structural canonical decode keeps the stale pin.
        var unresolved = CODEC.decodePayload(raw.toString().getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> SERVICE.unknown(VIEWER, SUBJECT, unresolved));
        assertThrows(IllegalArgumentException.class, () -> SERVICE.fullCode(unresolved, complete, ACCESS));
    }

    @Test
    void unknownExtensionsAndInvalidGenomesNeverGainDisclosureThroughAlways() {
        var always = new KnowledgeService(BUNDLE, policy(ServerConfig.Visibility.ALWAYS, true, true));
        var unknown = SERVICE.unknown(VIEWER, SUBJECT, WOLF);
        var ext = new GenesisBlueprint.OpaqueExtension(new RegistryFingerprint(new RulesetVersion(id("unknown_extension"), 1), "0".repeat(64)),
                1, ExtensionPayload.Kind.OPTIONAL_COSMETIC, new ImmutableBytes(new byte[]{0, (byte) 0xff}));
        var opaque = new GenesisBlueprint(WOLF.references(), WOLF.historicalForm(), WOLF.genome(), WOLF.visualSeed(), List.of(ext));
        var missing = new TreeMap<>(WOLF.genome().genes()); missing.remove(id("element"));
        var invalid = new GenesisBlueprint(WOLF.references(), WOLF.historicalForm(), new Genome(WOLF.genome().geneSchema(), WOLF.genome().familyId(), missing), 0, List.of());
        for (var bad : List.of(opaque, invalid)) {
            assertThrows(IllegalArgumentException.class, () -> always.unknown(VIEWER, SUBJECT, bad));
            assertThrows(IllegalArgumentException.class, () -> always.fullCode(bad, unknown, ACCESS));
        }
    }

    @Test
    void unknownFieldsAndNonvisualGenesCannotBeGrantedByObservationOrAnalysis() {
        var unknown = SERVICE.unknown(VIEWER, SUBJECT, WOLF);
        for (String invalid : List.of("arcana", "element", "heritage_1", "genesis:pattern", "pattern_carrier", "innateSpellPotential")) {
            assertThrows(IllegalArgumentException.class, () -> SERVICE.recordObservation(WOLF, unknown, ACCESS, Set.of(invalid)));
        }
        assertThrows(IllegalArgumentException.class, () -> SERVICE.recordAnalysis(WOLF, unknown, ACCESS, Set.of(id("unknown"))));
        var invalidObserved = new KnowledgeSnapshot(VIEWER, SUBJECT, unknown.blueprintSha256(), 0, Set.of("arcana"), Set.of());
        var invalidAnalyzed = new KnowledgeSnapshot(VIEWER, SUBJECT, unknown.blueprintSha256(), 0, Set.of(), Set.of(id("unknown")));
        for (var bad : List.of(invalidObserved, invalidAnalyzed)) {
            assertThrows(IllegalArgumentException.class, () -> SERVICE.passport(WOLF, bad, ACCESS));
            assertThrows(IllegalArgumentException.class, () -> SERVICE.analysis(WOLF, bad, ACCESS));
        }
    }

    @Test
    void updatesAreMonotoneCasProposalsWithNoOpForRepeatedFacts() {
        var initial = SERVICE.unknown(VIEWER, SUBJECT, WOLF);
        var observation = SERVICE.recordObservation(WOLF, initial, ACCESS, Set.of("pattern"));
        assertEquals(0, observation.expectedRevision()); assertEquals(1, observation.next().revision()); assertTrue(observation.changed());
        var repeat = SERVICE.recordObservation(WOLF, observation.next(), ACCESS, Set.of("pattern"));
        assertSame(observation.next(), repeat.next()); assertFalse(repeat.changed());
        var analysis = SERVICE.recordAnalysis(WOLF, observation.next(), ACCESS, Set.of(id("pattern")));
        assertEquals(1, analysis.expectedRevision()); assertEquals(2, analysis.next().revision());
        assertEquals(Set.of("pattern"), analysis.next().observedVisualSlots());
        var repeatAnalysis = SERVICE.recordAnalysis(WOLF, analysis.next(), ACCESS, Set.of(id("pattern")));
        assertSame(analysis.next(), repeatAnalysis.next()); assertFalse(repeatAnalysis.changed());
        assertTrue(initial.analyzedGenes().isEmpty()); assertTrue(initial.observedVisualSlots().isEmpty());
        var restored = new KnowledgeSnapshot(VIEWER, SUBJECT, analysis.next().blueprintSha256(), analysis.next().revision(),
                analysis.next().observedVisualSlots(), analysis.next().analyzedGenes());
        assertEquals(SERVICE.analysis(WOLF, analysis.next(), ACCESS), SERVICE.analysis(WOLF, restored, ACCESS));
    }

    @Test
    void allFamiliesRequireTheirComplete26Or27Or28PairsBeforeFullCode() {
        for (int i = 0; i < 28; i++) {
            var blueprint = CODEC.decode(VECTORS.getAsJsonArray("cases").get(i).getAsJsonObject().get("code").getAsString());
            var known = complete(blueprint);
            assertEquals(blueprint.genome().genes().size(), SERVICE.analysis(blueprint, known, ACCESS).knownGenes().size());
            assertEquals(blueprint, CODEC.decode(SERVICE.fullCode(blueprint, known, ACCESS)));
        }
    }

    @Test
    void immutableFactsAndDtosDoNotAliasMutableInputsAndParallelReadsDoNotGrantKnowledge() {
        var initial = SERVICE.unknown(VIEWER, SUBJECT, WOLF);
        var observed = new HashSet<>(Set.of("pattern")); var genes = new HashSet<>(Set.of(id("pattern")));
        var known = new KnowledgeSnapshot(VIEWER, SUBJECT, initial.blueprintSha256(), 1, observed, genes);
        observed.clear(); genes.clear();
        assertEquals(Set.of("pattern"), known.observedVisualSlots()); assertEquals(Set.of(id("pattern")), known.analyzedGenes());
        assertThrows(UnsupportedOperationException.class, () -> known.observedVisualSlots().clear());
        assertThrows(UnsupportedOperationException.class, () -> known.analyzedGenes().clear());
        assertThrows(UnsupportedOperationException.class, () -> SERVICE.passport(WOLF, known, ACCESS).observedVisualTraits().clear());
        assertThrows(UnsupportedOperationException.class, () -> SERVICE.analysis(WOLF, known, ACCESS).knownGenes().clear());
        var expected = SERVICE.passport(WOLF, known, ACCESS);
        java.util.stream.IntStream.range(0, 100).parallel().forEach(i -> {
            assertEquals(expected, SERVICE.passport(WOLF, known, ACCESS));
            assertFalse(SERVICE.analysis(WOLF, known, ACCESS).complete());
        });
        assertEquals(1, known.revision());
    }

    @Test
    void actualConfigReloadChangesFutureDisclosureWithoutRewritingFactsOrOldPolicy() {
        var runtime = new ConfigurationRuntime(BUNDLE, BUNDLE.defaults()); var before = runtime.snapshot().knowledgeService();
        var unknown = before.unknown(VIEWER, SUBJECT, WOLF);
        var config = JsonParser.parseString(new String(BUNDLE.documents().get("Genesis_GDD_v0.7_Server_Config.json").copy(), StandardCharsets.UTF_8)).getAsJsonObject();
        config.getAsJsonObject("genesisCode").addProperty("visibility", "always");
        runtime.reload(new ByteArrayInputStream(config.toString().getBytes(StandardCharsets.UTF_8)), "knowledge-test");
        assertEquals(CODEC.encode(WOLF), runtime.snapshot().knowledgeService().fullCode(WOLF, unknown, ACCESS));
        assertThrows(IllegalArgumentException.class, () -> before.fullCode(WOLF, unknown, ACCESS));
        assertFalse(runtime.snapshot().knowledgeService().analysis(WOLF, unknown, ACCESS).complete());
        config.getAsJsonObject("genesisCode").addProperty("visibility", "after_analysis");
        runtime.reload(new ByteArrayInputStream(config.toString().getBytes(StandardCharsets.UTF_8)), "knowledge-test");
        assertThrows(IllegalArgumentException.class, () -> runtime.snapshot().knowledgeService().fullCode(WOLF, unknown, ACCESS));
        assertTrue(unknown.analyzedGenes().isEmpty()); assertEquals(0, unknown.revision());
    }

    private static KnowledgeSnapshot complete(GenesisBlueprint blueprint) {
        return SERVICE.recordAnalysis(blueprint, SERVICE.unknown(VIEWER, SUBJECT, blueprint), ACCESS, blueprint.genome().genes().keySet()).next();
    }
    private static DisclosureAccess access(Set<DisclosureAccess.Permission> permissions) { return new DisclosureAccess(VIEWER, SUBJECT, 0, permissions); }
    private static ContentId id(String path) { return ContentId.parse("genesis:" + path); }
    private static GenesisBlueprint pair(GenesisBlueprint blueprint, String gene, String a, String b) {
        var schema = BUNDLE.families().get(blueprint.genome().familyId()); var pairs = new TreeMap<>(blueprint.genome().genes());
        pairs.put(id(gene), new AllelePair(id(a), id(b)));
        return new GenesisBlueprint(blueprint.references(), blueprint.historicalForm(), schema.createGenome(pairs), blueprint.visualSeed(), blueprint.extensions());
    }
    private static ServerConfig.Code policy(ServerConfig.Visibility visibility, boolean passport, boolean export) {
        return new ServerConfig.Code(visibility, export, passport, POLICY.playerSpawnFromCodeEnabled(), POLICY.adminSpawnEnabled(),
                POLICY.importMode(), POLICY.missingExtensions(), POLICY.limits());
    }
}
