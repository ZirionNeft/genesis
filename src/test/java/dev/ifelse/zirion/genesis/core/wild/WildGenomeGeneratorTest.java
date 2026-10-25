package dev.ifelse.zirion.genesis.core.wild;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ifelse.zirion.genesis.config.CatalogLoader;
import dev.ifelse.zirion.genesis.config.ConfigurationRuntime;
import dev.ifelse.zirion.genesis.core.config.ServerConfig;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.genetics.v2.MendelianInheritance;
import dev.ifelse.zirion.genesis.core.phenotype.v2.PhenotypeResolver;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.IntStream;

import static dev.ifelse.zirion.genesis.core.wild.WildDistributionPolicy.*;
import static dev.ifelse.zirion.genesis.core.wild.WildGenomeGenerator.*;
import static org.junit.jupiter.api.Assertions.*;

class WildGenomeGeneratorTest {
    private static final CatalogBundle BUNDLE = new CatalogLoader().loadBundled(WildGenomeGeneratorTest.class.getClassLoader());
    private static final JsonObject VECTORS = read("/wild-v2-vectors.json");
    private static final RulesetVersion POLICY_ID = new RulesetVersion(ContentId.parse("test:wild_fixture"), 1);
    private static final WildDistributionPolicy POLICY = fixturePolicy();
    private static final WildGenerationContext CONTEXT = new ConfigurationRuntime(BUNDLE, BUNDLE.defaults()).snapshot().wildContext(POLICY);
    private static final WildGenomeGenerator GENERATOR = new WildGenomeGenerator(CONTEXT);
    private static final ContentId WOLF = id("wolf");
    private static final WildCandidateRecord.NpcIdentity NPC = new WildCandidateRecord.NpcIdentity(id("test_identity"), new UUID(0, 1), new UUID(0, 2));

    @Test
    void independentPythonVectorsPinPolicyHashFullGenomesAndExactGateBits() {
        assertEquals(VECTORS.get("policySha256").getAsString(), POLICY.reference().sha256());
        assertEquals(VECTORS.getAsJsonObject("schema").get("sha256").getAsString(), POLICY.geneSchema().sha256());
        assertEquals(80, VECTORS.getAsJsonArray("cases").size());
        for (var entry : VECTORS.getAsJsonArray("cases")) {
            var row = entry.getAsJsonObject(); var founder = ContentId.parse(row.get("founder").getAsString());
            var request = request(founder, Long.parseLong(row.get("seed").getAsString()), Affinity.valueOf(row.get("affinity").getAsString()));
            var result = GENERATOR.observe(request, Optional.empty());
            assertEquals(row.get("gateBits").getAsString(), String.format("%016x", Double.doubleToRawLongBits(draw(request, "arcana_gate"))));
            assertEquals(genome(row), result.genome(), founder + " seed=" + request.seed());
            assertEquals(BUNDLE.hybridGraph().identity(founder), result.form());
        }
    }

    @Test
    void allTwelveFoundersHavePureHeritageAndWorkWithInheritanceFormsAndPotential() {
        var resolver = new PhenotypeResolver(BUNDLE);
        for (var founder : POLICY.founders().keySet()) {
            var candidate = GENERATOR.observe(request(founder, 42, Affinity.ORDINARY), Optional.empty());
            var schema = BUNDLE.families().get(candidate.genome().familyId());
            assertEquals(Map.of(founder, 6), schema.founderCopyCounts(candidate.genome()));
            var child = MendelianInheritance.inherit(candidate.genome(), candidate.genome(), schema, () -> false);
            schema.validate(child); assertEquals(Map.of(founder, 6), schema.founderCopyCounts(child));
            assertEquals(founder, resolver.resolve(candidate.form(), candidate.genome()).forms().effectiveSignatureForm().id());
            assertEquals(BUNDLE.defaults().genetics().registries().geneSchema().fingerprint(), candidate.genome().geneSchema());
        }
    }

    @Test
    void existingRecordWinsOverChangedSeedCandidateIdAffinityAndEvenMissingNewProfile() {
        var first = GENERATOR.observe(request(WOLF, 42, Affinity.ORDINARY), Optional.empty());
        var onlyFox = new WildDistributionPolicy(POLICY_ID, POLICY.geneSchema(), Map.of(id("fox"), POLICY.founders().get(id("fox"))));
        var changed = new WildGenomeGenerator(new WildGenerationContext(19, arcana("1", "1"), BUNDLE, onlyFox));
        var again = new Request(NPC, new UUID(9, 9), WOLF, Affinity.AFFINITY_TAGGED, -999);
        assertSame(first, changed.observe(again, Optional.of(first)));
        assertThrows(IllegalArgumentException.class, () -> changed.observe(again, Optional.empty()));
        assertEquals(42, first.replay().request().seed()); assertSame(CONTEXT, first.replay().context());
    }

    @Test
    void wrongWorldEntityProviderOrSpeciesCannotReuseAnUnrelatedRecord() {
        var first = GENERATOR.observe(request(WOLF, 42, Affinity.ORDINARY), Optional.empty());
        for (var wrong : List.of(new WildCandidateRecord.NpcIdentity(id("other"), NPC.worldId(), NPC.entityId()),
                new WildCandidateRecord.NpcIdentity(NPC.provider(), new UUID(0, 7), NPC.entityId()),
                new WildCandidateRecord.NpcIdentity(NPC.provider(), NPC.worldId(), new UUID(0, 7)))) {
            assertThrows(IllegalArgumentException.class, () -> GENERATOR.observe(new Request(wrong, first.candidateId(), WOLF, Affinity.ORDINARY, 42), Optional.of(first)));
        }
        assertThrows(IllegalArgumentException.class, () -> GENERATOR.observe(request(id("fox"), 42, Affinity.ORDINARY), Optional.of(first)));
    }

    @Test
    void replayRetainsOldConfigurationAfterActualReloadAndIsDeterministicInParallel() {
        var runtime = new ConfigurationRuntime(BUNDLE, BUNDLE.defaults());
        var retained = new WildGenomeGenerator(runtime.snapshot().wildContext(POLICY));
        var request = request(WOLF, 42, Affinity.ORDINARY);
        var first = retained.observe(request, Optional.empty());
        var json = JsonParser.parseString(new String(BUNDLE.documents().get("Genesis_GDD_v0.7_Server_Config.json").copy(), StandardCharsets.UTF_8)).getAsJsonObject();
        json.getAsJsonObject("genetics").getAsJsonObject("wildArcana").addProperty("ordinaryCandidateRate", BigDecimal.ONE);
        runtime.reload(new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8)), "test");
        assertEquals(1, runtime.snapshot().revision());
        assertNotEquals(first.genome().genes().get(ARCANA), new WildGenomeGenerator(runtime.snapshot().wildContext(POLICY)).observe(request, Optional.empty()).genome().genes().get(ARCANA));
        IntStream.range(0, 100).parallel().forEach(i -> {
            assertEquals(first.genome(), WildGenomeGenerator.replay(first.replay()).genome());
            assertSame(first, retained.observe(request, Optional.of(first)));
        });
        assertEquals(0, first.replay().context().configRevision());
    }

    @Test
    void probabilitiesZeroAndOneApplyOnceToWholeCandidateIncludingConditionalDosage() {
        var never = new WildGenomeGenerator(new WildGenerationContext(1, arcana("0", "0"), BUNDLE, POLICY));
        var always = new WildGenomeGenerator(new WildGenerationContext(2, arcana("1", "1"), BUNDLE, POLICY));
        var awakePairs = new java.util.HashSet<AllelePair>();
        for (long seed = 0; seed < 200; seed++) {
            for (var affinity : Affinity.values()) {
                var request = request(WOLF, seed, affinity);
                assertEquals(new AllelePair(QUIET, QUIET), never.observe(request, Optional.empty()).genome().genes().get(ARCANA));
                var awake = always.observe(request, Optional.empty()).genome().genes().get(ARCANA);
                assertTrue(awake.first().equals(AWAKE)); awakePairs.add(awake);
            }
        }
        assertEquals(SetHolder.AWAKENED, awakePairs);
    }

    @Test
    void configuredThreeAndTwelvePercentAreCandidateRatesAndDosageIsConditional() {
        int ordinary = 0, affinity = 0, homo = 0; int samples = 40000;
        for (long seed = 0; seed < samples; seed++) {
            var request = request(WOLF, seed, Affinity.ORDINARY);
            BigDecimal gate = new BigDecimal(draw(request, "arcana_gate"));
            if (gate.compareTo(CONTEXT.arcana().ordinaryCandidateRate()) < 0) ordinary++;
            if (gate.compareTo(CONTEXT.arcana().affinityCandidateRate()) < 0) {
                affinity++;
                if (POLICY.founders().get(WOLF).awakenedArcana().sample(draw(request, "arcana_awake_pair")).equals(new AllelePair(AWAKE, AWAKE))) homo++;
            }
        }
        withinSixSigma(ordinary, samples, .03); withinSixSigma(affinity, samples, .12); withinSixSigma(homo, affinity, .25);
        assertTrue(affinity >= ordinary);
    }

    @Test
    void fullPairTablesRequireExplicitZerosAndExcludeForeignVariants() {
        var profile = POLICY.founders().get(WOLF); var genes = new TreeMap<>(profile.genes()); var gene = id("body_frame");
        var missingZero = new HashMap<>(genes.get(gene).probabilities());
        missingZero.entrySet().removeIf(e -> e.getValue().signum() == 0);
        genes.put(gene, new Distribution(missingZero));
        assertInvalid(new Profile(genes, profile.awakenedArcana()));
        var foreign = new HashMap<>(profile.genes().get(gene).probabilities());
        foreign.put(new AllelePair(id("not_registered"), id("not_registered")), BigDecimal.ZERO);
        genes.put(gene, new Distribution(foreign)); assertInvalid(new Profile(genes, profile.awakenedArcana()));
    }

    @Test
    void missingExtraArcanaAndHeritageGeneDistributionsAreRejected() {
        var profile = POLICY.founders().get(WOLF);
        for (var gene : List.of(id("element"), id("heritage_1"), ARCANA, id("wing_form"))) {
            var genes = new TreeMap<>(profile.genes());
            if (genes.containsKey(gene)) genes.remove(gene); else genes.put(gene, profile.awakenedArcana());
            assertInvalid(new Profile(genes, profile.awakenedArcana()));
        }
        var quiet = new Distribution(Map.of(new AllelePair(QUIET, QUIET), BigDecimal.ONE));
        assertInvalid(new Profile(profile.genes(), quiet));
        assertInvalid(new Profile(profile.genes(), new Distribution(Map.of(new AllelePair(AWAKE, QUIET), BigDecimal.ONE))));
    }

    @Test
    void hybridUnknownFounderAndWrongSchemaNeverBecomeWildProfiles() {
        for (var founder : List.of(id("wolf_fox"), id("unknown"))) {
            var policy = new WildDistributionPolicy(POLICY_ID, POLICY.geneSchema(), Map.of(founder, POLICY.founders().get(WOLF)));
            assertThrows(IllegalArgumentException.class, () -> new WildGenerationContext(0, CONTEXT.arcana(), BUNDLE, policy));
        }
        var wrong = new WildDistributionPolicy(POLICY_ID, new RegistryFingerprint(POLICY.geneSchema().reference(), "0".repeat(64)), POLICY.founders());
        assertThrows(IllegalArgumentException.class, () -> wrong.validate(BUNDLE));
        assertThrows(IllegalArgumentException.class, () -> new WildDistributionPolicy(POLICY_ID, POLICY.geneSchema(), Map.of()));
        var wolfForRaven = new WildDistributionPolicy(POLICY_ID, POLICY.geneSchema(), Map.of(id("raven"), POLICY.founders().get(WOLF)));
        assertThrows(IllegalArgumentException.class, () -> wolfForRaven.validate(BUNDLE));
    }

    @Test
    void distributionsRejectInvalidTotalsRangesAndExtremePrecision() {
        var pair = new AllelePair(AWAKE, QUIET);
        for (var value : List.of("0", "-0.1", "1.1", "0.9999999999999999999999", "1E-1025", "0E+1025")) {
            assertThrows(IllegalArgumentException.class, () -> new Distribution(Map.of(pair, new BigDecimal(value))));
        }
        assertThrows(IllegalArgumentException.class, () -> new Distribution(Map.of()));
    }

    @Test
    void weightedSamplingUsesExactDoubleIntervalsAndNeverSelectsZeroWeight() {
        var table = POLICY.founders().get(WOLF).awakenedArcana();
        assertEquals(new AllelePair(AWAKE, AWAKE), table.sample(Math.nextDown(.25)));
        assertEquals(new AllelePair(AWAKE, QUIET), table.sample(.25));
        var genes = POLICY.founders().get(WOLF).genes(); var normal = genes.get(id("body_frame"));
        assertTrue(normal.probabilities().get(normal.sample(0)).signum() > 0);
        for (double invalid : new double[]{1, -Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> table.sample(invalid));
        }
        // Binary64 0.03 is below exact decimal .03, so it belongs to the first interval.
        var precise = new Distribution(Map.of(new AllelePair(AWAKE, AWAKE), new BigDecimal(".03"), new AllelePair(AWAKE, QUIET), new BigDecimal(".97")));
        assertEquals(new AllelePair(AWAKE, AWAKE), precise.sample(.03));
        assertEquals(new AllelePair(AWAKE, QUIET), precise.sample(Math.nextUp(.03)));
    }

    @Test
    void policyIsDeeplyImmutableAndFingerprintIgnoresInsertionOrderAndDecimalScale() {
        var profiles = new HashMap<>(POLICY.founders()); var genes = new HashMap<>(profiles.get(WOLF).genes());
        var table = new HashMap<>(genes.get(id("body_frame")).probabilities());
        table.replaceAll((pair, value) -> value.setScale(5));
        var dist = new Distribution(table); table.clear(); genes.put(id("body_frame"), dist);
        profiles.put(WOLF, new Profile(genes, profiles.get(WOLF).awakenedArcana())); genes.clear();
        var copy = new WildDistributionPolicy(POLICY_ID, POLICY.geneSchema(), profiles); profiles.clear();
        assertEquals(POLICY.reference(), copy.reference());
        assertThrows(UnsupportedOperationException.class, () -> copy.founders().clear());
        assertThrows(UnsupportedOperationException.class, () -> copy.founders().get(WOLF).genes().clear());
        assertThrows(UnsupportedOperationException.class, () -> dist.probabilities().clear());
        var changed = new HashMap<>(POLICY.founders()); var profile = changed.get(WOLF);
        changed.put(WOLF, new Profile(profile.genes(), new Distribution(Map.of(new AllelePair(AWAKE, AWAKE), BigDecimal.ONE, new AllelePair(AWAKE, QUIET), BigDecimal.ZERO))));
        assertNotEquals(POLICY.reference(), new WildDistributionPolicy(POLICY_ID, POLICY.geneSchema(), changed).reference());
        assertNotEquals(POLICY.reference(), new WildDistributionPolicy(new RulesetVersion(POLICY_ID.id(), 2), POLICY.geneSchema(), POLICY.founders()).reference());
    }

    @Test
    void unknownAlgorithmAndArcanaDistributionVersionAreRejected() {
        var record = GENERATOR.observe(request(WOLF, 42, Affinity.ORDINARY), Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> WildGenomeGenerator.replay(new Replay(new RulesetVersion(ALGORITHM.id(), 3), CONTEXT, record.replay().request())));
        var unknown = new ServerConfig.WildArcana(new BigDecimal(".03"), new BigDecimal(".12"), id("unknown_distribution"));
        assertThrows(IllegalArgumentException.class, () -> new WildGenerationContext(0, unknown, BUNDLE, POLICY));
    }

    @Test
    void changingOtherGeneWeightsDoesNotConsumeArcanaOrElementRandomness() {
        var profile = POLICY.founders().get(WOLF); var genes = new TreeMap<>(profile.genes());
        var pairs = new TreeMap<AllelePair, BigDecimal>(java.util.Comparator.comparing(AllelePair::first).thenComparing(AllelePair::second));
        pairs.putAll(genes.get(id("body_frame")).probabilities());
        var firstPair = pairs.firstKey(); pairs.replaceAll((pair, weight) -> pair.equals(firstPair) ? BigDecimal.ONE : BigDecimal.ZERO);
        genes.put(id("body_frame"), new Distribution(pairs));
        var policy = new WildDistributionPolicy(POLICY_ID, POLICY.geneSchema(), Map.of(WOLF, new Profile(genes, profile.awakenedArcana())));
        var changed = new WildGenomeGenerator(new WildGenerationContext(1, CONTEXT.arcana(), BUNDLE, policy));
        for (int seed = 0; seed < 100; seed++) {
            var request = request(WOLF, seed, Affinity.AFFINITY_TAGGED);
            var original = GENERATOR.observe(request, Optional.empty()).genome(); var after = changed.observe(request, Optional.empty()).genome();
            assertEquals(firstPair, after.genes().get(id("body_frame")));
            original.genes().forEach((gene, pair) -> { if (!gene.equals(id("body_frame"))) assertEquals(pair, after.genes().get(gene)); });
        }
    }

    private static WildDistributionPolicy fixturePolicy() {
        var founders = new TreeMap<ContentId, Profile>();
        for (var schema : BUNDLE.families().values()) {
            var genes = new TreeMap<ContentId, Distribution>();
            schema.genes().forEach((gene, definition) -> {
                if (FamilyGeneSchema.HERITAGE_GENES.contains(gene) || gene.equals(ARCANA)) return;
                var variants = definition.variants().stream().sorted().toList(); var pairs = new java.util.ArrayList<AllelePair>();
                for (int a = 0; a < variants.size(); a++) for (int b = a; b < variants.size(); b++) pairs.add(new AllelePair(variants.get(a), variants.get(b)));
                var weights = new HashMap<AllelePair, BigDecimal>();
                for (int i = 0; i < pairs.size(); i++) weights.put(pairs.get(i), i == 0 ? BigDecimal.ZERO : i < pairs.size() - 1
                        ? new BigDecimal(".05") : BigDecimal.ONE.subtract(new BigDecimal(".05").multiply(BigDecimal.valueOf(pairs.size() - 2))));
                genes.put(gene, new Distribution(weights));
            });
            var awake = new Distribution(Map.of(new AllelePair(AWAKE, AWAKE), new BigDecimal(".25"), new AllelePair(AWAKE, QUIET), new BigDecimal(".75")));
            schema.founders().forEach(founder -> founders.put(founder, new Profile(genes, awake)));
        }
        return new WildDistributionPolicy(POLICY_ID, BUNDLE.defaults().genetics().registries().geneSchema().fingerprint(), founders);
    }

    private static void assertInvalid(Profile profile) {
        var policy = new WildDistributionPolicy(POLICY_ID, POLICY.geneSchema(), Map.of(WOLF, profile));
        assertThrows(IllegalArgumentException.class, () -> new WildGenerationContext(0, CONTEXT.arcana(), BUNDLE, policy));
    }

    private static ServerConfig.WildArcana arcana(String ordinary, String affinity) {
        return new ServerConfig.WildArcana(new BigDecimal(ordinary), new BigDecimal(affinity), ARCANA_DISTRIBUTION);
    }

    private static Request request(ContentId founder, long seed, Affinity affinity) { return new Request(NPC, new UUID(0, 3), founder, affinity, seed); }
    private static ContentId id(String path) { return ContentId.parse("genesis:" + path); }
    private static void withinSixSigma(int observed, int n, double p) { assertTrue(Math.abs(observed - n * p) <= 6 * Math.sqrt(n * p * (1 - p)), "count=" + observed); }
    private static JsonObject read(String path) { return JsonParser.parseReader(new InputStreamReader(WildGenomeGeneratorTest.class.getResourceAsStream(path), StandardCharsets.UTF_8)).getAsJsonObject(); }
    private static Genome genome(JsonObject row) {
        var genes = new TreeMap<ContentId, AllelePair>();
        row.getAsJsonObject("genome").entrySet().forEach(e -> { var pair = e.getValue().getAsJsonArray(); genes.put(ContentId.parse(e.getKey()), new AllelePair(ContentId.parse(pair.get(0).getAsString()), ContentId.parse(pair.get(1).getAsString()))); });
        return BUNDLE.families().get(ContentId.parse(row.get("family").getAsString())).createGenome(genes);
    }
    private static final class SetHolder {
        private static final java.util.Set<AllelePair> AWAKENED = java.util.Set.of(new AllelePair(AWAKE, AWAKE), new AllelePair(AWAKE, QUIET));
    }
}
