package dev.ifelse.zirion.genesis.core.breeding.v2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ifelse.zirion.genesis.config.CatalogLoader;
import dev.ifelse.zirion.genesis.config.ConfigLoader;
import dev.ifelse.zirion.genesis.config.ConfigurationRuntime;
import dev.ifelse.zirion.genesis.core.breeding.AlleleRandom;
import dev.ifelse.zirion.genesis.core.breeding.GenerationSeed;
import dev.ifelse.zirion.genesis.core.code.ExtensionPayload;
import dev.ifelse.zirion.genesis.core.code.ImmutableBytes;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.content.v2.FormIdentity;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RegistryFingerprint;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import dev.ifelse.zirion.genesis.core.genetics.v2.GeneDefinition;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.phenotype.v2.Stat;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import static dev.ifelse.zirion.genesis.core.breeding.v2.BreedingWorkflow.*;
import static org.junit.jupiter.api.Assertions.*;

class BreedingWorkflowTest {
    private static final CatalogBundle BUNDLE = new CatalogLoader().loadBundled(BreedingWorkflowTest.class.getClassLoader());
    private static final BreedingContext CONTEXT = new BreedingContext(0, BUNDLE.defaults(), BUNDLE);
    private static final BreedingWorkflow WORKFLOW = new BreedingWorkflow(CONTEXT, Set.of());
    private static final UUID A = new UUID(0, 1), B = new UUID(0, 2), CHILD = new UUID(0, 3), OP = new UUID(0, 4);

    @Test
    void completeGenomesAcrossAllFamiliesAndSignedSeedExtremesMatchPython() throws Exception {
        try (var reader = new InputStreamReader(getClass().getResourceAsStream("/breeding-v2-vectors.json"), StandardCharsets.UTF_8)) {
            var vectors = JsonParser.parseReader(reader).getAsJsonObject();
            var pins = vectors.getAsJsonObject("registries");
            assertEquals(CONTEXT.config().genetics().registries().geneSchema().sha256(), pins.getAsJsonObject("geneSchema").get("sha256").getAsString());
            assertEquals(CONTEXT.config().genetics().registries().expressionRules().sha256(), pins.getAsJsonObject("expressionRules").get("sha256").getAsString());
            int success = 0, failures = 0;
            for (var entry : vectors.getAsJsonArray("cases")) {
                var vector = entry.getAsJsonObject();
                var schema = BUNDLE.families().get(ContentId.parse(vector.get("family").getAsString()));
                // Descending UUID order: the workflow must retain caller A/B order instead of sorting parents.
                var a = new Parent(B, BUNDLE.hybridGraph().identity(ContentId.parse(vector.get("formA").getAsString())), genome(schema, vector.getAsJsonObject("parentA")), List.of());
                var b = new Parent(A, BUNDLE.hybridGraph().identity(ContentId.parse(vector.get("formB").getAsString())), genome(schema, vector.getAsJsonObject("parentB")), List.of());
                var result = WORKFLOW.calculate(new Request(OP, CHILD, a, b, conditions("1", "1", "1"),
                        Approach.auto(CONTEXT.config().breeding().autoUnlockAfterFamilySuccesses()), new GenerationSeed(Long.parseLong(vector.get("seed").getAsString()))));
                double draw;
                if (vector.get("success").getAsBoolean()) {
                    var pending = assertInstanceOf(AwaitingMutation.class, result);
                    assertEquals(genome(schema, vector.getAsJsonObject("inherited")), pending.inherited());
                    assertEquals(Long.parseLong(vector.get("mutationSeed").getAsString()), pending.mutationSeed());
                    assertEquals(Long.parseLong(vector.get("cosmeticSeed").getAsString()), pending.cosmeticSeed());
                    decimal(vector.get("chance").getAsString(), pending.quote().successProbability());
                    assertEquals(result, BreedingWorkflow.replay(pending.replay()));
                    draw = pending.attemptDraw(); success++;
                } else {
                    var failed = assertInstanceOf(Failed.class, result); draw = failed.attemptDraw(); failures++;
                    assertEquals(result, BreedingWorkflow.replay(failed.replay()));
                }
                assertEquals(Long.parseUnsignedLong(vector.get("drawBits").getAsString(), 16), Double.doubleToLongBits(draw));
            }
            assertEquals(28, success + failures); assertTrue(success > 0); assertTrue(failures > 0);
        }
    }

    @Test
    void knownF1HasFixedHistorySixCopiesPotentialAndPedigreeWithoutKinshipGate() {
        var pending = assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(request()));
        assertEquals(id("wolf_fox"), pending.historicalForm().historicalForm());
        assertEquals(Map.of(id("wolf"), 3, id("fox"), 3), pending.inheritedPotential().forms().founderCopyCounts());
        decimal("100", pending.inheritedPotential().stats().get(Stat.HP));
        decimal("5.65", pending.inheritedPotential().stats().get(Stat.SPEED_BPS));
        assertEquals(CHILD, pending.pedigree().creatureId()); assertEquals(Optional.of(A), pending.pedigree().parentA());
        assertEquals(Optional.of(B), pending.pedigree().parentB()); assertFalse(pending.pedigree().referenceFounder());
        assertSame(CONTEXT, pending.replay().context()); assertEquals(ALGORITHMS, pending.replay().algorithms());
    }

    @Test
    void everyExplicitGraphPairPassesBeforeSamplingAndUsesExactlyTwoChoicesPerGene() {
        int count = 0;
        for (var pair : BUNDLE.hybridGraph().pairs().entrySet()) {
            var a = parent(A, pair.getKey().first()); var b = parent(B, pair.getKey().second());
            var streams = new CountingStreams(0);
            var result = assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(withParents(request(), a, b), streams));
            assertEquals(pair.getValue(), result.historicalForm().historicalForm());
            assertEquals(1, streams.attempts); assertEquals(1, streams.inheritanceCalls);
            assertEquals(2 * a.genome().genes().size(), streams.choices); count++;
        }
        assertEquals(112, count);
    }

    @Test
    void repeatedInterleavedAndConcurrentCalculationsDoNotShareRngState() throws Exception {
        var original = WORKFLOW.calculate(request());
        for (long value : new long[]{0, -1, Long.MIN_VALUE, Long.MAX_VALUE}) {
            var r = request(); WORKFLOW.calculate(new Request(OP, CHILD, r.parentA(), r.parentB(), r.conditions(), r.approach(), new GenerationSeed(value)));
            assertEquals(original, WORKFLOW.calculate(request()));
        }
        try (var executor = Executors.newFixedThreadPool(3)) {
            var tasks = new ArrayList<java.util.concurrent.Future<Result>>();
            for (int i = 0; i < 12; i++) tasks.add(executor.submit(() -> WORKFLOW.calculate(request())));
            for (var task : tasks) assertEquals(original, task.get());
        }
    }

    @Test
    void runtimeReloadChangesFutureAttemptsAndPreservesTheRecordedReplay() {
        var runtime = new ConfigurationRuntime(BUNDLE, BUNDLE.defaults());
        var original = assertInstanceOf(AwaitingMutation.class,
                new BreedingWorkflow(runtime.snapshot().breedingContext(), Set.of()).calculate(request()));
        var config = configJson();
        config.getAsJsonObject("breeding").addProperty("baseSameFamilySuccess", bd("0.01"));
        config.getAsJsonObject("breeding").addProperty("baseCooldownMinutes", 20);
        runtime.reload(new ByteArrayInputStream(config.toString().getBytes(StandardCharsets.UTF_8)), "reload");
        var r = request();
        var nextRequest = new Request(new UUID(0, 5), new UUID(0, 6), r.parentA(), r.parentB(), r.conditions(), r.approach(), r.seed());
        var future = assertInstanceOf(Failed.class,
                new BreedingWorkflow(runtime.snapshot().breedingContext(), Set.of()).calculate(nextRequest));
        decimal("0.01", future.quote().successProbability()); decimal("20", future.quote().cooldownMinutes());
        assertEquals(1, future.replay().context().configRevision());
        decimal("0.7", original.quote().successProbability()); decimal("10", original.quote().cooldownMinutes());
        assertEquals(0, original.replay().context().configRevision());
        assertEquals(original, BreedingWorkflow.replay(original.replay()));
    }

    @Test
    void duplicateParentAndOffspringIdentityRejectBeforeRandomness() {
        var r = request();
        rejected(WORKFLOW, withParents(r, r.parentA(), r.parentA()), Reason.SAME_PARENT);
        rejected(WORKFLOW, new Request(OP, A, r.parentA(), r.parentB(), r.conditions(), r.approach(), r.seed()), Reason.OFFSPRING_IS_PARENT);
    }

    @Test
    void invalidGenesGraphPinsAndFamilyRejectBeforeRandomness() {
        var r = request(); var a = r.parentA(); var schema = BUNDLE.families().get(id("canids"));
        var invalid = new TreeMap<>(a.genome().genes()); invalid.remove(id("muscle"));
        rejected(WORKFLOW, withParents(r, new Parent(A, a.form(), new Genome(schema.reference(), schema.familyId(), invalid), List.of()), r.parentB()), Reason.INVALID_PARENT);
        var unknown = new TreeMap<>(a.genome().genes()); unknown.put(id("muscle"), new AllelePair(id("unknown"), id("unknown")));
        rejected(WORKFLOW, withParents(r, new Parent(A, a.form(), new Genome(schema.reference(), schema.familyId(), unknown), List.of()), r.parentB()), Reason.INVALID_PARENT);
        var stale = new RegistryFingerprint(a.form().hybridGraph().reference(), "0".repeat(64));
        rejected(WORKFLOW, withParents(r, new Parent(A, new FormIdentity(stale, a.form().historicalForm()), a.genome(), List.of()), r.parentB()), Reason.INVALID_PARENT);
        rejected(WORKFLOW, withParents(r, parent(A, id("horse")), r.parentB()), Reason.INCOMPATIBLE_FAMILY);
        rejected(WORKFLOW, withParents(r, new Parent(A, BUNDLE.hybridGraph().identity(id("fox")), a.genome(), List.of()), r.parentB()), Reason.INVALID_PARENT);
    }

    @Test
    void opaqueExtensionsAndTrustedRequiredDependenciesRejectWithoutLosingBytes() {
        var key = new ExtensionPayload.Key(new RulesetVersion(id("unknown_extension"), 1), 1);
        byte[] bytes = {0, -1, 7};
        var payload = new ExtensionPayload(key, ExtensionPayload.Kind.OPTIONAL_COSMETIC, new ImmutableBytes(bytes));
        var r = request(); var original = r.parentA(); var extensions = new ArrayList<>(List.of(payload));
        var a = new Parent(A, original.form(), original.genome(), extensions); extensions.clear();
        rejected(WORKFLOW, withParents(r, a, r.parentB()), Reason.UNSUPPORTED_EXTENSIONS);
        rejected(new BreedingWorkflow(CONTEXT, Set.of(key)), r, Reason.UNSUPPORTED_EXTENSIONS);
        assertArrayEquals(bytes, a.extensions().getFirst().payload().copy());
        assertThrows(UnsupportedOperationException.class, () -> a.extensions().clear());
    }

    @Test
    void enableModeAndAutoUnlockFlagsAreCheckedBeforeRng() {
        var r = request();
        rejected(workflow(config -> config.addProperty("enabled", false)), r, Reason.BREEDING_DISABLED);
        rejected(workflow(config -> config.addProperty("autoBreedingEnabled", false)), r, Reason.AUTO_DISABLED);
        var locked = withApproach(r, Approach.auto(CONTEXT.config().breeding().autoUnlockAfterFamilySuccesses() - 1));
        rejected(WORKFLOW, locked, Reason.AUTO_LOCKED);
        var manual = withApproach(r, Approach.manual(bd("80")));
        var noManual = workflow(config -> config.addProperty("manualMinigameEnabled", false));
        rejected(noManual, manual, Reason.MANUAL_DISABLED);
        assertInstanceOf(AwaitingMutation.class, noManual.calculate(withApproach(r, Approach.auto(0)), new CountingStreams(0)));
    }

    @Test
    void readinessAndEnvironmentBoundsAreEnforcedWithoutClampingInputs() {
        var r = request();
        for (String value : new String[]{"0.7999", "1.1001"}) {
            rejected(WORKFLOW, withConditions(r, conditions("1", "1", value)), Reason.ENVIRONMENT_OUT_OF_RANGE);
        }
        for (String value : new String[]{"0.8", "1.1"}) {
            assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(withConditions(r, conditions("1", "1", value)), new CountingStreams(0)));
        }
        assertThrows(IllegalArgumentException.class, () -> conditions("-0.1", "1", "1"));
        assertThrows(IllegalArgumentException.class, () -> conditions("1", "1.01", "1"));
        assertThrows(IllegalArgumentException.class, () -> Approach.manual(bd("100.01")));
        assertThrows(IllegalArgumentException.class, () -> Approach.auto(-1));
    }

    @Test
    void quoteUsesExactConfigFormulaAndCooldownThreshold() {
        var r = withApproach(withConditions(request(), conditions("0.8", "0.5", "1.1")), Approach.manual(bd("80")));
        var pending = assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(r, new CountingStreams(0)));
        decimal("0.3388", pending.quote().successProbability()); decimal("1.10", pending.quote().harmonyMultiplier());
        decimal("9", pending.quote().cooldownMinutes());
    }

    @Test
    void compatibilityCoefficientUsesHistoricalFormInsteadOfGeneticSupport() {
        var fox = parent(A, id("fox")); var b = new Parent(B, BUNDLE.hybridGraph().identity(id("wolf_fox")), fox.genome(), List.of());
        var different = assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(withParents(request(), fox, b), new CountingStreams(0)));
        decimal("0.7", different.quote().successProbability());
        var a = new Parent(A, b.form(), request().parentA().genome(), List.of());
        var same = assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(withParents(request(), a, b), new CountingStreams(0)));
        decimal("0.9", same.quote().successProbability());
    }

    @Test
    void minigameOnlyChangesQuoteAndLeavesInheritanceAndOtherSeedsAlone() {
        var base = request();
        var automatic = assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(base));
        for (String score : new String[]{"0", "80", "100"}) {
            var manual = assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(withApproach(base, Approach.manual(bd(score)))));
            assertEquals(automatic.inherited(), manual.inherited()); assertEquals(automatic.inheritedPotential(), manual.inheritedPotential());
            assertEquals(automatic.mutationSeed(), manual.mutationSeed()); assertEquals(automatic.cosmeticSeed(), manual.cosmeticSeed());
            assertEquals(automatic.attemptDraw(), manual.attemptDraw());
        }
    }

    @Test
    void cappedChanceStillAwardsManualCooldownAndHonorsConfiguredFloor() {
        var r = withParents(request(), parent(A, id("wolf")), parent(B, id("wolf")));
        r = withConditions(r, conditions("1", "1", "1.1"));
        var below = assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(withApproach(r, Approach.manual(bd("79.99"))), new CountingStreams(0)));
        var at = assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(withApproach(r, Approach.manual(bd("80"))), new CountingStreams(0)));
        decimal("0.95", below.quote().successProbability()); decimal("0.95", at.quote().successProbability());
        decimal("10", below.quote().cooldownMinutes()); decimal("9", at.quote().cooldownMinutes());
        var floor = workflow(config -> config.addProperty("manualCooldownReduction", 1));
        var result = assertInstanceOf(AwaitingMutation.class, floor.calculate(withApproach(r, Approach.manual(bd("100"))), new CountingStreams(0)));
        decimal("5", result.quote().cooldownMinutes());
    }

    @Test
    void zeroProbabilityAndFailedBoundaryConsumeOnlyOneAttemptDraw() {
        var streams = new CountingStreams(0);
        assertInstanceOf(Failed.class, WORKFLOW.calculate(withConditions(request(), conditions("0", "1", "1")), streams));
        assertEquals(1, streams.attempts); assertEquals(0, streams.inheritanceCalls);
        var half = workflow(config -> config.addProperty("baseSameFamilySuccess", bd("0.5")));
        var equal = new CountingStreams(0.5);
        assertInstanceOf(Failed.class, half.calculate(request(), equal)); assertEquals(0, equal.choices);
        assertInstanceOf(AwaitingMutation.class, half.calculate(request(), new CountingStreams(Math.nextDown(0.5))));
        // Binary double 0.7 is a possible n/2^53 draw, slightly below the exact decimal probability 0.7.
        assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(request(), new CountingStreams(0.7)));
    }

    @Test
    void invalidAttemptDrawFailsWithoutSamplingInheritance() {
        for (double draw : new double[]{Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, -0.01, 1}) {
            var streams = new CountingStreams(draw);
            assertThrows(IllegalArgumentException.class, () -> WORKFLOW.calculate(request(), streams));
            assertEquals(1, streams.attempts); assertEquals(0, streams.inheritanceCalls);
        }
    }

    @Test
    void unknownReplayAlgorithmsAndMismatchedConfigPinsCannotFallBack() {
        var pending = assertInstanceOf(AwaitingMutation.class, WORKFLOW.calculate(request())); var replay = pending.replay();
        for (var algorithms : List.of(
                new Algorithms(new RulesetVersion(ALGORITHM.id(), 99), ALGORITHMS.generationSeed(), ALGORITHMS.alleleRandom(), ALGORITHMS.inheritance()),
                new Algorithms(ALGORITHM, new RulesetVersion(GenerationSeed.ALGORITHM, 99), ALGORITHMS.alleleRandom(), ALGORITHMS.inheritance()),
                new Algorithms(ALGORITHM, ALGORITHMS.generationSeed(), id("unknown_rng"), ALGORITHMS.inheritance()),
                new Algorithms(ALGORITHM, ALGORITHMS.generationSeed(), ALGORITHMS.alleleRandom(), id("unknown_inheritance")))) {
            assertThrows(IllegalArgumentException.class, () -> BreedingWorkflow.replay(new Replay(algorithms, replay.context(), replay.request(), replay.requiredExtensions())));
        }
        var config = configJson(); config.getAsJsonObject("genetics").getAsJsonObject("registries").getAsJsonObject("geneSchema").addProperty("sha256", "0".repeat(64));
        var invalid = new ConfigLoader().load(new ByteArrayInputStream(config.toString().getBytes(StandardCharsets.UTF_8)), "test");
        assertThrows(IllegalArgumentException.class, () -> new BreedingContext(0, invalid, BUNDLE));
        assertThrows(IllegalArgumentException.class, () -> new BreedingContext(-1, BUNDLE.defaults(), BUNDLE));
    }

    private static Request request() {
        return new Request(OP, CHILD, parent(A, id("wolf")), parent(B, id("fox")), conditions("1", "1", "1"),
                Approach.auto(CONTEXT.config().breeding().autoUnlockAfterFamilySuccesses()), new GenerationSeed(42));
    }
    private static Parent parent(UUID creatureId, ContentId formId) {
        var form = BUNDLE.hybridGraph().form(formId); var schema = BUNDLE.families().get(form.familyId());
        ContentId founder = form.roots().iterator().next();
        var defaults = new TreeMap<ContentId, ContentId>();
        var content = JsonParser.parseString(new String(BUNDLE.documents().get("Genesis_v0.7_Stage_08B_Content_Catalog_v1.json").copy(), StandardCharsets.UTF_8)).getAsJsonObject();
        for (var row : content.getAsJsonArray("genes")) {
            var gene = row.getAsJsonObject(); defaults.put(id(gene.get("id").getAsString().toLowerCase(java.util.Locale.ROOT)), id(gene.get("defaultVariant").getAsString()));
        }
        var pairs = new TreeMap<ContentId, AllelePair>();
        schema.genes().forEach((gene, definition) -> {
            var variant = definition.kind() == GeneDefinition.Kind.HERITAGE ? founder : defaults.getOrDefault(gene, definition.variants().iterator().next());
            pairs.put(gene, new AllelePair(variant, variant));
        });
        return new Parent(creatureId, BUNDLE.hybridGraph().identity(formId), schema.createGenome(pairs), List.of());
    }
    private static Genome genome(FamilyGeneSchema schema, JsonObject json) {
        var pairs = new TreeMap<ContentId, AllelePair>();
        json.entrySet().forEach(entry -> {
            var pair = entry.getValue().getAsJsonArray();
            pairs.put(ContentId.parse(entry.getKey()), new AllelePair(ContentId.parse(pair.get(0).getAsString()), ContentId.parse(pair.get(1).getAsString())));
        });
        return schema.createGenome(pairs);
    }
    private static BreedingWorkflow workflow(Consumer<JsonObject> changes) {
        var config = configJson(); changes.accept(config.getAsJsonObject("breeding"));
        var decoded = new ConfigLoader().load(new ByteArrayInputStream(config.toString().getBytes(StandardCharsets.UTF_8)), "test");
        return new BreedingWorkflow(new BreedingContext(1, decoded, BUNDLE), Set.of());
    }
    private static JsonObject configJson() {
        return JsonParser.parseString(new String(BUNDLE.documents().get("Genesis_GDD_v0.7_Server_Config.json").copy(), StandardCharsets.UTF_8)).getAsJsonObject();
    }
    private static void rejected(BreedingWorkflow workflow, Request request, Reason reason) {
        var streams = new CountingStreams(0);
        assertEquals(new Rejected(reason), workflow.calculate(request, streams));
        assertEquals(0, streams.attempts); assertEquals(0, streams.inheritanceCalls); assertEquals(0, streams.choices);
    }
    private static final class CountingStreams implements Streams {
        private final double draw; int attempts, inheritanceCalls, choices;
        CountingStreams(double draw) { this.draw = draw; }
        public double attempt() { attempts++; return draw; }
        public AlleleRandom inheritance() { inheritanceCalls++; return () -> { choices++; return false; }; }
    }
    private static Conditions conditions(String a, String b, String environment) { return new Conditions(bd(a), bd(b), bd(environment)); }
    private static Request withParents(Request r, Parent a, Parent b) { return new Request(r.operationId(), r.offspringId(), a, b, r.conditions(), r.approach(), r.seed()); }
    private static Request withApproach(Request r, Approach approach) { return new Request(r.operationId(), r.offspringId(), r.parentA(), r.parentB(), r.conditions(), approach, r.seed()); }
    private static Request withConditions(Request r, Conditions conditions) { return new Request(r.operationId(), r.offspringId(), r.parentA(), r.parentB(), conditions, r.approach(), r.seed()); }
    private static ContentId id(String path) { return ContentId.parse("genesis:" + path); }
    private static BigDecimal bd(String value) { return new BigDecimal(value); }
    private static void decimal(String expected, BigDecimal actual) { assertEquals(0, bd(expected).compareTo(actual), expected + " != " + actual); }
}
