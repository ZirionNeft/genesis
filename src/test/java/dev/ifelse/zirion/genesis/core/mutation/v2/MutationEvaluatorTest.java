package dev.ifelse.zirion.genesis.core.mutation.v2;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.ifelse.zirion.genesis.config.CatalogLoader;
import dev.ifelse.zirion.genesis.core.content.v2.CatalogBundle;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.v2.Genome;
import dev.ifelse.zirion.genesis.core.mutation.MutationRandom;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static dev.ifelse.zirion.genesis.core.mutation.v2.MutationCatalog.Category;
import static org.junit.jupiter.api.Assertions.*;

class MutationEvaluatorTest {
    private static final CatalogBundle BUNDLE = new CatalogLoader().loadBundled(MutationEvaluatorTest.class.getClassLoader());
    private static final JsonObject VECTORS = read("/mutation-v2-vectors.json");
    private static final dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema SCHEMA = BUNDLE.families().get(id("canids"));
    private static final Genome GENOME = genome(VECTORS.getAsJsonObject("isolatedInput"));
    private static final MutationEvaluator EVALUATOR = new MutationEvaluator(BUNDLE.mutations(), SCHEMA, BUNDLE.defaults().genetics().mutation());

    @Test
    void all21MutationsAndBothCanonicalCopiesMatchIndependentPythonVectors() {
        assertEquals(BUNDLE.contentCatalogSha256(), VECTORS.get("contentSha256").getAsString());
        assertEquals(42, VECTORS.getAsJsonArray("events").size());
        var ids = new java.util.HashSet<ContentId>();
        for (var entry : VECTORS.getAsJsonArray("events")) {
            var row = entry.getAsJsonObject(); var expected = row.getAsJsonObject("expected");
            var result = EVALUATOR.apply(GENOME, research(ResearchSnapshot.Tier.BASE, false, 0, false), Long.parseLong(row.get("seed").getAsString()));
            var change = result.change().orElseThrow(); ids.add(change.mutationId());
            assertEquals(expected.get("id").getAsString(), change.mutationId().toString());
            assertEquals(expected.get("gene").getAsString(), change.gene().toString());
            assertEquals(expected.get("copy").getAsInt(), change.copyIndex());
            assertEquals(expected.get("previous").getAsString(), change.previous().toString());
            assertEquals(expected.get("target").getAsString(), change.target().toString());
            var pairs = new TreeMap<>(GENOME.genes());
            var after = expected.getAsJsonArray("after");
            pairs.put(change.gene(), new AllelePair(ContentId.parse(after.get(0).getAsString()), ContentId.parse(after.get(1).getAsString())));
            assertEquals(SCHEMA.createGenome(pairs), result.genome());
            assertEquals(expected.get("award").getAsBoolean(), result.discovery().awardFirstDiscovery());
            assertFalse(change.guaranteed()); assertHeritageUnchanged(GENOME, result.genome());
        }
        assertEquals(21, ids.size());
    }

    @Test
    void categoryIntervalsAndEmptyCategoriesDoNotReroll() {
        assertEquals(Category.COSMETIC, apply(0.01).change().orElseThrow().category());
        assertEquals(Category.PHYSIOLOGICAL, apply(0.07).change().orElseThrow().category());
        assertEquals(Category.BEHAVIORAL, apply(0.11).change().orElseThrow().category());
        assertEquals(Category.MAGICAL, apply(0.15).change().orElseThrow().category());
        assertEquals(MutationEvaluator.UnchangedReason.NO_EVENT, apply(0.9).unchangedReason().orElseThrow());
        var pairs = new TreeMap<>(GENOME.genes());
        BUNDLE.mutations().definitions().values().stream().filter(m -> m.category() == Category.COSMETIC)
                .forEach(m -> pairs.put(m.gene(), new AllelePair(m.target(), m.target())));
        var random = new Scripted(0.01);
        var result = EVALUATOR.apply(SCHEMA.createGenome(pairs), research(ResearchSnapshot.Tier.BASE, false, 0, false), random);
        assertEquals(MutationEvaluator.UnchangedReason.CATEGORY_UNAVAILABLE, result.unchangedReason().orElseThrow());
        assertEquals(1, random.units); assertEquals(0, random.indices);
    }

    @Test
    void selectionIsUniformByEligibleIdAndAdverseVariantsShareTheCatalogCategory() {
        int physiological = 0;
        for (int index = 0; index < 9; index++) {
            var rng = new Scripted(0.07, index, 0);
            var result = EVALUATOR.apply(GENOME, research(ResearchSnapshot.Tier.BASE, false, 0, false), rng);
            var expected = BUNDLE.mutations().definitions().values().stream().filter(m -> m.category() == Category.PHYSIOLOGICAL).toList().get(index);
            assertEquals(expected.id(), result.change().orElseThrow().mutationId());
            assertEquals(List.of(9, 2), rng.bounds); physiological++;
        }
        assertEquals(9, physiological);
    }

    @Test
    void existingTargetCopyIsNotSelectedAndHomozygousTargetIsIneligible() {
        var definition = BUNDLE.mutations().definitions().get(id("arcane_awakening"));
        var pairs = new TreeMap<>(GENOME.genes()); pairs.put(definition.gene(), new AllelePair(id("awake"), id("quiet")));
        var rng = new Scripted(0.15, 0, 0);
        var result = EVALUATOR.apply(SCHEMA.createGenome(pairs), research(ResearchSnapshot.Tier.BASE, false, 0, false), rng);
        assertEquals(1, result.change().orElseThrow().copyIndex()); assertEquals(List.of(1, 1), rng.bounds);
        assertEquals(new AllelePair(id("awake"), id("awake")), result.genome().genes().get(definition.gene()));
        var no = EVALUATOR.apply(result.genome(), research(ResearchSnapshot.Tier.BASE, false, 0, false), new Scripted(0.15));
        assertEquals(MutationEvaluator.UnchangedReason.CATEGORY_UNAVAILABLE, no.unchangedReason().orElseThrow());
    }

    @Test
    void seventhQualifyingSuccessForcesOnlyOneArcanaCopyWithoutCategoryDraw() {
        var rng = new Scripted(0.99, 0, 1);
        var before = research(ResearchSnapshot.Tier.MASTERY, true, 6, false);
        var result = EVALUATOR.apply(GENOME, before, rng);
        assertEquals(0, rng.units); assertEquals(2, rng.indices);
        var change = result.change().orElseThrow(); assertEquals(id("arcane_awakening"), change.mutationId()); assertTrue(change.guaranteed());
        assertEquals(7, result.discovery().qualifyingSuccesses()); assertTrue(result.discovery().firstDiscoveryAwarded());
        assertTrue(result.discovery().awardFirstDiscovery()); assertEquals(before.revision(), result.discovery().expectedRevision());
        assertEquals(1, GENOME.genes().entrySet().stream().filter(e -> !e.getValue().equals(result.genome().genes().get(e.getKey()))).count());
        assertEquals(GENOME.genes().get(id("element")), result.genome().genes().get(id("element")));
        assertHeritageUnchanged(GENOME, result.genome());
    }

    @Test
    void firstSixCountOnlyQualifyingBirthsAndOrdinaryDiscoveryCanFinishEarly() {
        for (int count = 0; count < 6; count++) {
            var rng = new Scripted(0.99);
            var result = EVALUATOR.apply(GENOME, research(ResearchSnapshot.Tier.MASTERY, true, count, false), rng);
            assertTrue(result.change().isEmpty()); assertEquals(count + 1, result.discovery().qualifyingSuccesses());
            assertFalse(result.discovery().firstDiscoveryAwarded()); assertEquals(1, rng.units);
        }
        var ordinary = EVALUATOR.apply(GENOME, research(ResearchSnapshot.Tier.MASTERY, true, 2, false), new Scripted(0.2, 0, 0));
        assertTrue(ordinary.discovery().awardFirstDiscovery()); assertEquals(3, ordinary.discovery().qualifyingSuccesses());
        assertFalse(ordinary.change().orElseThrow().guaranteed());
        var after = EVALUATOR.apply(GENOME, research(ResearchSnapshot.Tier.MASTERY, true, 3, true), new Scripted(0.99));
        assertEquals(3, after.discovery().qualifyingSuccesses()); assertTrue(after.discovery().firstDiscoveryAwarded());
        assertFalse(after.discovery().awardFirstDiscovery());
        var cosmetic = EVALUATOR.apply(GENOME, research(ResearchSnapshot.Tier.MASTERY, true, 5, false), new Scripted(0.01, 0, 0));
        assertEquals(Category.COSMETIC, cosmetic.change().orElseThrow().category());
        assertEquals(6, cosmetic.discovery().qualifyingSuccesses()); assertFalse(cosmetic.discovery().awardFirstDiscovery());
    }

    @Test
    void nonMasteryUnfocusedAlreadyAwakeAndAwardedBirthsDoNotQualify() {
        for (var research : List.of(research(ResearchSnapshot.Tier.BASE, true, 6, false), research(ResearchSnapshot.Tier.DISCOVERY, true, 6, false),
                research(ResearchSnapshot.Tier.MASTERY, false, 6, false), research(ResearchSnapshot.Tier.MASTERY, true, 6, true))) {
            var result = EVALUATOR.apply(GENOME, research, new Scripted(0.99));
            assertTrue(result.change().isEmpty()); assertFalse(result.discovery().qualifyingBirth()); assertEquals(6, result.discovery().qualifyingSuccesses());
        }
        var pairs = new TreeMap<>(GENOME.genes()); pairs.put(id("arcana"), new AllelePair(id("awake"), id("quiet")));
        var result = EVALUATOR.apply(SCHEMA.createGenome(pairs), research(ResearchSnapshot.Tier.MASTERY, true, 6, false), new Scripted(0.99));
        assertFalse(result.discovery().qualifyingBirth()); assertTrue(result.change().isEmpty());
    }

    @Test
    void administrativeAndImportedOriginsNeverSpendGuaranteeOrAwardDiscovery() {
        for (var origin : List.of(ResearchSnapshot.Origin.ADMIN, ResearchSnapshot.Origin.IMPORT)) {
            var r = new ResearchSnapshot(new UUID(0, 1), id("canids"), 4, ResearchSnapshot.Tier.MASTERY, true, origin, 6, false);
            var result = EVALUATOR.apply(GENOME, r, new Scripted(0.2, 0, 0));
            assertTrue(result.change().isPresent()); assertFalse(result.change().orElseThrow().guaranteed());
            assertFalse(result.discovery().qualifyingBirth()); assertFalse(result.discovery().awardFirstDiscovery());
            assertFalse(result.discovery().firstDiscoveryAwarded()); assertEquals(6, result.discovery().qualifyingSuccesses());
        }
    }

    @Test
    void invalidResearchAndGenomesRejectBeforeMutationRng() {
        var rng = new Scripted(0.1);
        var r = new ResearchSnapshot(new UUID(0, 1), id("runners"), 0, ResearchSnapshot.Tier.BASE, false, ResearchSnapshot.Origin.BREEDING, 0, false);
        assertThrows(IllegalArgumentException.class, () -> EVALUATOR.apply(GENOME, r, rng));
        assertEquals(0, rng.units); assertEquals(0, rng.indices);
        assertThrows(IllegalArgumentException.class, () -> research(ResearchSnapshot.Tier.BASE, false, -1, false));
    }

    @Test
    void immutableTargetsCannotMutateHeritageOrRemoveRequiredAwakening() {
        var original = BUNDLE.mutations(); var targets = new TreeMap<>(original.definitions());
        var definition = targets.get(id("dark_coat"));
        targets.put(definition.id(), new MutationCatalog.Definition(definition.id(), definition.category(), id("heritage_1"), id("wolf")));
        var invalid = new MutationCatalog(original.contentId(), original.contentSha256(), original.geneSchema(), original.selectionId(), targets);
        assertThrows(IllegalArgumentException.class, () -> invalid.validate(BUNDLE.families()));
        targets = new TreeMap<>(original.definitions()); targets.remove(id("arcane_awakening"));
        var missing = new MutationCatalog(original.contentId(), original.contentSha256(), original.geneSchema(), original.selectionId(), targets);
        assertThrows(IllegalArgumentException.class, () -> missing.validate(BUNDLE.families()));
        assertThrows(UnsupportedOperationException.class, () -> original.definitions().clear());
    }

    @Test
    void unavailableGlobalTargetsConsumeNoRngAndInvalidSamplesFailClosed() {
        var original = BUNDLE.mutations(); var onlyMagic = new MutationCatalog(original.contentId(), original.contentSha256(), original.geneSchema(), original.selectionId(),
                Map.of(id("arcane_awakening"), original.definitions().get(id("arcane_awakening"))));
        var pairs = new TreeMap<>(GENOME.genes()); pairs.put(id("arcana"), new AllelePair(id("awake"), id("awake")));
        var rng = new Scripted(0.01);
        var result = new MutationEvaluator(onlyMagic, SCHEMA, BUNDLE.defaults().genetics().mutation()).apply(SCHEMA.createGenome(pairs), research(ResearchSnapshot.Tier.BASE, false, 0, false), rng);
        assertEquals(MutationEvaluator.UnchangedReason.NO_ELIGIBLE_MUTATIONS, result.unchangedReason().orElseThrow());
        assertEquals(0, rng.units); assertEquals(0, rng.indices);
        for (double draw : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -0.1, 1}) {
            assertThrows(IllegalArgumentException.class, () -> EVALUATOR.apply(GENOME, research(ResearchSnapshot.Tier.BASE, false, 0, false), new Scripted(draw)));
        }
        assertThrows(IllegalArgumentException.class, () -> EVALUATOR.apply(GENOME, research(ResearchSnapshot.Tier.BASE, false, 0, false), new Scripted(0.01, 6)));
    }

    @Test
    void measuredTierCategoryAndIdFrequenciesMatchExclusiveProbabilities() {
        int samples = 40_000;
        for (var tier : ResearchSnapshot.Tier.values()) {
            var r = research(tier, false, 0, false);
            var categories = new EnumMap<Category, Integer>(Category.class); var ids = new TreeMap<ContentId, Integer>();
            for (long seed = 0; seed < samples; seed++) {
                // Mix sequential test seeds before Java's 48-bit initialization; do not sample correlated first draws.
                long mixed = new dev.ifelse.zirion.genesis.core.breeding.GenerationSeed(seed).forStream(dev.ifelse.zirion.genesis.core.breeding.GenerationSeed.Stream.MUTATION);
                var result = EVALUATOR.apply(GENOME, r, mixed);
                result.change().ifPresent(change -> { categories.merge(change.category(), 1, Integer::sum); ids.merge(change.mutationId(), 1, Integer::sum); });
                assertHeritageUnchanged(GENOME, result.genome());
            }
            var p = switch (tier) { case BASE -> BUNDLE.defaults().genetics().mutation().probabilityByTier().base();
                case DISCOVERY -> BUNDLE.defaults().genetics().mutation().probabilityByTier().discovery(); case MASTERY -> BUNDLE.defaults().genetics().mutation().probabilityByTier().mastery(); };
            for (Category category : Category.values()) {
                double probability = switch (category) { case COSMETIC -> p.cosmetic().doubleValue(); case PHYSIOLOGICAL -> p.physiological().doubleValue(); case BEHAVIORAL -> p.behavioral().doubleValue(); case MAGICAL -> p.magical().doubleValue(); };
                withinSixSigma(categories.getOrDefault(category, 0), samples, probability);
                var mutations = BUNDLE.mutations().definitions().values().stream().filter(m -> m.category() == category).toList();
                for (var mutation : mutations) withinSixSigma(ids.getOrDefault(mutation.id(), 0), samples, probability / mutations.size());
            }
        }
    }

    private static MutationEvaluator.Result apply(double draw) { return EVALUATOR.apply(GENOME, research(ResearchSnapshot.Tier.BASE, false, 0, false), new Scripted(draw, 0, 0)); }
    private static ResearchSnapshot research(ResearchSnapshot.Tier tier, boolean focused, int count, boolean awarded) {
        return new ResearchSnapshot(new UUID(0, 10), id("canids"), 8, tier, focused, ResearchSnapshot.Origin.BREEDING, count, awarded);
    }
    private static Genome genome(JsonObject json) {
        var pairs = new TreeMap<ContentId, AllelePair>(); json.entrySet().forEach(entry -> {
            var pair = entry.getValue().getAsJsonArray(); pairs.put(ContentId.parse(entry.getKey()), new AllelePair(ContentId.parse(pair.get(0).getAsString()), ContentId.parse(pair.get(1).getAsString())));
        });
        return BUNDLE.families().get(id("canids")).createGenome(pairs);
    }
    private static void assertHeritageUnchanged(Genome a, Genome b) {
        for (var gene : dev.ifelse.zirion.genesis.core.genetics.v2.FamilyGeneSchema.HERITAGE_GENES) assertEquals(a.genes().get(gene), b.genes().get(gene));
    }
    private static void withinSixSigma(int actual, int n, double p) {
        assertTrue(Math.abs(actual - n * p) <= 6 * Math.sqrt(n * p * (1 - p)) + 1, actual + " vs " + n * p);
    }
    private static final class Scripted implements MutationRandom {
        final double draw; final int[] values; int units, indices; final java.util.ArrayList<Integer> bounds = new java.util.ArrayList<>();
        Scripted(double draw, int... values) { this.draw = draw; this.values = values; }
        public double nextUnit() { units++; return draw; }
        public int nextIndex(int bound) { bounds.add(bound); return values[indices++]; }
    }
    private static JsonObject read(String path) {
        try (var reader = new InputStreamReader(MutationEvaluatorTest.class.getResourceAsStream(path), StandardCharsets.UTF_8)) { return JsonParser.parseReader(reader).getAsJsonObject(); }
        catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }
    private static ContentId id(String path) { return ContentId.parse("genesis:" + path); }
}
