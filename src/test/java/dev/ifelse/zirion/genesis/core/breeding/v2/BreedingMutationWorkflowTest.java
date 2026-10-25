package dev.ifelse.zirion.genesis.core.breeding.v2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ifelse.zirion.genesis.config.CatalogLoader;
import dev.ifelse.zirion.genesis.config.ConfigurationRuntime;
import dev.ifelse.zirion.genesis.core.breeding.GenerationSeed;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.mutation.v2.ResearchSnapshot;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Executors;

import static dev.ifelse.zirion.genesis.core.breeding.v2.BreedingMutationWorkflow.*;
import static org.junit.jupiter.api.Assertions.*;

class BreedingMutationWorkflowTest {
    private static final CatalogBundle BUNDLE = new CatalogLoader().loadBundled(BreedingMutationWorkflowTest.class.getClassLoader());
    private static final BreedingContext CONTEXT = new BreedingContext(0, BUNDLE.defaults(), BUNDLE);
    private static final JsonObject INPUTS = read("/breeding-v2-vectors.json");
    private static final JsonObject VECTORS = read("/mutation-v2-vectors.json");
    private static final UUID PLAYER = new UUID(0, 8);
    private static final BreedingMutationWorkflow WORKFLOW = new BreedingMutationWorkflow(CONTEXT, Set.of());

    @Test
    void fullWorkflowAcrossFourFamiliesMatchesIndependentMutationVectorsAndReplays() {
        for (var entry : VECTORS.getAsJsonArray("workflow")) {
            var row = entry.getAsJsonObject(); var request = request(row.get("inputIndex").getAsInt());
            var result = WORKFLOW.calculate(request);
            if (row.get("expected").isJsonNull()) {
                var failed = assertInstanceOf(Failed.class, result); assertEquals(result, BreedingMutationWorkflow.replay(failed.replay()));
                continue;
            }
            var child = assertInstanceOf(Conceived.class, result); var expected = row.getAsJsonObject("expected");
            assertEquals(BUNDLE.expressionRules().references(), child.potential().references());
            assertEquals(result, BreedingMutationWorkflow.replay(child.replay()));
            assertEquals(expected.get("award").getAsBoolean(), child.mutation().discovery().awardFirstDiscovery());
            var pairs = new TreeMap<>(child.inherited().inherited().genes());
            if (expected.has("id")) {
                var change = child.mutation().change().orElseThrow();
                assertEquals(expected.get("id").getAsString(), change.mutationId().toString());
                assertEquals(expected.get("copy").getAsInt(), change.copyIndex());
                var after = expected.getAsJsonArray("after");
                pairs.put(ContentId.parse(expected.get("gene").getAsString()), new AllelePair(ContentId.parse(after.get(0).getAsString()), ContentId.parse(after.get(1).getAsString())));
            } else {
                assertTrue(child.mutation().change().isEmpty());
                assertEquals(expected.get("reason").getAsString(), child.mutation().unchangedReason().orElseThrow().name());
            }
            assertEquals(BUNDLE.families().get(request.research().familyId()).createGenome(pairs), child.genome());
            for (var gene : FamilyGeneSchema.HERITAGE_GENES) assertEquals(child.inherited().inherited().genes().get(gene), child.genome().genes().get(gene));
        }
    }

    @Test
    void guaranteeChangesOnlyArcanaAndRecomputesTheFinalPotential() {
        var request = qualifying(6);
        var child = assertInstanceOf(Conceived.class, WORKFLOW.calculate(request));
        assertTrue(child.mutation().change().orElseThrow().guaranteed());
        assertEquals(ContentId.parse("genesis:arcane_awakening"), child.mutation().change().orElseThrow().mutationId());
        assertEquals(7, child.mutation().discovery().qualifyingSuccesses());
        assertTrue(child.mutation().discovery().awardFirstDiscovery());
        assertTrue(child.inherited().inheritedPotential().innateSpellPotential().isEmpty());
        assertEquals(Optional.of(ContentId.parse("genesis:stone")), child.potential().innateSpellPotential());
        assertEquals(child.inherited().inheritedPotential().stats(), child.potential().stats());
        assertEquals(0, request.research().revision()); assertFalse(request.research().firstDiscoveryAwarded());
        assertEquals(child, BreedingMutationWorkflow.replay(child.replay()));
    }

    @Test
    void failedAttemptCannotAdvanceTheGuaranteeAndForeignFamilyIsRejected() {
        var runtime = new ConfigurationRuntime(BUNDLE, BUNDLE.defaults());
        var cfg = config(); cfg.getAsJsonObject("breeding").addProperty("maxSuccessProbability", 0);
        runtime.reload(stream(cfg), "zero");
        var r = qualifying(6);
        var failed = assertInstanceOf(Failed.class, new BreedingMutationWorkflow(runtime.snapshot().breedingContext(), Set.of()).calculate(r));
        assertEquals(6, failed.replay().request().research().qualifyingSuccesses());
        assertFalse(failed.replay().request().research().firstDiscoveryAwarded());
        var foreign = new ResearchSnapshot(PLAYER, ContentId.parse("genesis:runners"), 0, ResearchSnapshot.Tier.MASTERY, true, ResearchSnapshot.Origin.BREEDING, 6, false);
        assertEquals(new Rejected(Reason.RESEARCH_FAMILY_MISMATCH, Optional.empty()), WORKFLOW.calculate(new Request(r.breeding(), foreign)));
    }

    @Test
    void reloadCannotReplaceCapturedProbabilitiesOrThresholdAndDisabledFocusStopsGuarantee() {
        var runtime = new ConfigurationRuntime(BUNDLE, BUNDLE.defaults());
        var original = assertInstanceOf(Conceived.class, WORKFLOW.calculate(qualifying(6)));
        var cfg = config();
        cfg.getAsJsonObject("genetics").getAsJsonObject("mutation").getAsJsonObject("arcaneFocus").addProperty("enabled", false);
        for (var tier : cfg.getAsJsonObject("genetics").getAsJsonObject("mutation").getAsJsonObject("probabilityByTier").entrySet()) {
            for (String category : List.of("cosmetic", "physiological", "behavioral", "magical")) tier.getValue().getAsJsonObject().addProperty(category, 0);
        }
        runtime.reload(stream(cfg), "no-mutations");
        var next = assertInstanceOf(Conceived.class, new BreedingMutationWorkflow(runtime.snapshot().breedingContext(), Set.of()).calculate(qualifying(6)));
        assertTrue(next.mutation().change().isEmpty()); assertEquals(6, next.mutation().discovery().qualifyingSuccesses());
        assertEquals(original, BreedingMutationWorkflow.replay(original.replay()));
    }

    @Test
    void configuredGuaranteeThresholdAndZeroOrdinaryMagicProbabilityAreRespected() {
        var runtime = new ConfigurationRuntime(BUNDLE, BUNDLE.defaults()); var cfg = config();
        cfg.getAsJsonObject("genetics").getAsJsonObject("mutation").getAsJsonObject("arcaneFocus").addProperty("firstDiscoveryGuaranteeEligibleSuccessfulBirths", 1);
        cfg.getAsJsonObject("genetics").getAsJsonObject("mutation").getAsJsonObject("probabilityByTier").getAsJsonObject("mastery").addProperty("magical", 0);
        runtime.reload(stream(cfg), "early-focus");
        var result = assertInstanceOf(Conceived.class, new BreedingMutationWorkflow(runtime.snapshot().breedingContext(), Set.of()).calculate(qualifying(0)));
        assertTrue(result.mutation().change().orElseThrow().guaranteed()); assertEquals(1, result.mutation().discovery().qualifyingSuccesses());
    }

    @Test
    void minigameDoesNotChangeMutationAndParallelReplayIsPure() throws Exception {
        var r = qualifying(6); var original = assertInstanceOf(Conceived.class, WORKFLOW.calculate(r));
        for (String score : List.of("0", "80", "100")) {
            var b = r.breeding(); var manual = new BreedingWorkflow.Request(b.operationId(), b.offspringId(), b.parentA(), b.parentB(), b.conditions(), BreedingWorkflow.Approach.manual(new java.math.BigDecimal(score)), b.seed());
            var next = assertInstanceOf(Conceived.class, WORKFLOW.calculate(new Request(manual, r.research())));
            assertEquals(original.genome(), next.genome()); assertEquals(original.mutation(), next.mutation());
        }
        try (var pool = Executors.newFixedThreadPool(3)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Result>>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> BreedingMutationWorkflow.replay(original.replay())));
            for (var future : futures) assertEquals(original, future.get());
        }
    }

    @Test
    void unknownFullWorkflowAlgorithmDoesNotReuseLegacyOrCurrentRulesSilently() {
        var child = assertInstanceOf(Conceived.class, WORKFLOW.calculate(qualifying(6))); var before = child.replay();
        var a = ALGORITHMS;
        for (var unsupported : List.of(
                new Algorithms(new RulesetVersion(ALGORITHM.id(), 99), a.inheritance(), a.mutation(), a.selection(), a.mutationRandom()),
                new Algorithms(a.workflow(), a.inheritance(), new RulesetVersion(a.mutation().id(), 99), a.selection(), a.mutationRandom()),
                new Algorithms(a.workflow(), a.inheritance(), a.mutation(), ContentId.parse("test:selection"), a.mutationRandom()))) {
            assertThrows(IllegalArgumentException.class, () -> BreedingMutationWorkflow.replay(new Replay(unsupported, before.context(), before.request(), before.requiredExtensions())));
        }
    }

    private static Request qualifying(int count) {
        var schema = BUNDLE.families().get(ContentId.parse("genesis:canids"));
        var input = VECTORS.getAsJsonObject("isolatedInput"); var genome = genome(schema, input);
        var form = BUNDLE.hybridGraph().identity(ContentId.parse("genesis:wolf"));
        var a = new BreedingWorkflow.Parent(new UUID(0, 1), form, genome, List.of());
        var b = new BreedingWorkflow.Parent(new UUID(0, 2), form, genome, List.of());
        var request = new BreedingWorkflow.Request(new UUID(0, 4), new UUID(0, 3), a, b,
                new BreedingWorkflow.Conditions(java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE),
                BreedingWorkflow.Approach.auto(BUNDLE.defaults().breeding().autoUnlockAfterFamilySuccesses()), new GenerationSeed(42));
        return new Request(request, new ResearchSnapshot(PLAYER, schema.familyId(), 0, ResearchSnapshot.Tier.MASTERY, true, ResearchSnapshot.Origin.BREEDING, count, false));
    }
    private static Request request(int index) {
        var row = INPUTS.getAsJsonArray("cases").get(index).getAsJsonObject(); var family = ContentId.parse(row.get("family").getAsString());
        var schema = BUNDLE.families().get(family);
        var a = new BreedingWorkflow.Parent(new UUID(0, 2), BUNDLE.hybridGraph().identity(ContentId.parse(row.get("formA").getAsString())), genome(schema, row.getAsJsonObject("parentA")), List.of());
        var b = new BreedingWorkflow.Parent(new UUID(0, 1), BUNDLE.hybridGraph().identity(ContentId.parse(row.get("formB").getAsString())), genome(schema, row.getAsJsonObject("parentB")), List.of());
        var request = new BreedingWorkflow.Request(new UUID(0, 4), new UUID(0, 3), a, b,
                new BreedingWorkflow.Conditions(java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE),
                BreedingWorkflow.Approach.auto(BUNDLE.defaults().breeding().autoUnlockAfterFamilySuccesses()), new GenerationSeed(Long.parseLong(row.get("seed").getAsString())));
        return new Request(request, new ResearchSnapshot(PLAYER, family, 0, ResearchSnapshot.Tier.BASE, false, ResearchSnapshot.Origin.BREEDING, 0, false));
    }
    private static Genome genome(FamilyGeneSchema schema, JsonObject json) {
        var pairs = new TreeMap<ContentId, AllelePair>(); json.entrySet().forEach(entry -> {
            var pair = entry.getValue().getAsJsonArray(); pairs.put(ContentId.parse(entry.getKey()), new AllelePair(ContentId.parse(pair.get(0).getAsString()), ContentId.parse(pair.get(1).getAsString())));
        }); return schema.createGenome(pairs);
    }
    private static JsonObject read(String path) {
        try (var r = new InputStreamReader(BreedingMutationWorkflowTest.class.getResourceAsStream(path), StandardCharsets.UTF_8)) { return JsonParser.parseReader(r).getAsJsonObject(); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    private static JsonObject config() { return JsonParser.parseString(new String(BUNDLE.documents().get("Genesis_GDD_v0.7_Server_Config.json").copy(), StandardCharsets.UTF_8)).getAsJsonObject(); }
    private static ByteArrayInputStream stream(JsonObject json) { return new ByteArrayInputStream(json.toString().getBytes(StandardCharsets.UTF_8)); }
}
