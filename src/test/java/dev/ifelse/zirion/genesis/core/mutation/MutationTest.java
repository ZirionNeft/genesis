package dev.ifelse.zirion.genesis.core.mutation;

import dev.ifelse.zirion.genesis.core.breeding.MendelianInheritance;
import dev.ifelse.zirion.genesis.core.genetics.AlleleDefinition;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.GeneticsRuleset;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.Locus;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static dev.ifelse.zirion.genesis.core.mutation.MutationCategory.*;
import static dev.ifelse.zirion.genesis.core.mutation.MutationDefinition.Impact;
import static dev.ifelse.zirion.genesis.core.mutation.MutationDefinition.Impact.BENEFICIAL;
import static dev.ifelse.zirion.genesis.core.mutation.MutationDefinition.Impact.TRADEOFF;
import static dev.ifelse.zirion.genesis.core.mutation.MutationDefinition.Impact.HARMFUL;
import static dev.ifelse.zirion.genesis.core.mutation.MutationRules.*;
import static org.junit.jupiter.api.Assertions.*;

/** Full synthetic tables include mutant alleles; they are not the final canid balance/content. */
class MutationTest {
    private static final RulesetVersion VERSION = new RulesetVersion(ContentId.parse("fixture:mutant_genetics"), 1);
    private static final RulesetVersion MUTATIONS = new RulesetVersion(ContentId.parse("fixture:mutations"), 1);
    private static final List<String> VARIANTS = List.of("a", "b", "m_good", "m_trade", "m_bad", "m_cosmetic");
    private static final GeneticsRuleset GENETICS = genetics();
    private static final Genome BASELINE = genome();
    private static final Map<Impact, Double> WEIGHTS = MutationPolicy.gddDefaults().impactChances();

    private static ContentId allele(Locus locus, String name) {
        return ContentId.parse("fixture:" + locus.name().toLowerCase(Locale.ROOT) + "/" + name);
    }

    private static AllelePair pair(Locus locus, String a, String b) {
        return new AllelePair(allele(locus, a), allele(locus, b));
    }

    private static GeneticsRuleset genetics() {
        var builder = GeneticsRuleset.builder(VERSION);
        for (Locus locus : Locus.values()) {
            for (String variant : VARIANTS) builder.registerAllele("fixture", new AlleleDefinition(allele(locus, variant), locus));
            for (int first = 0; first < VARIANTS.size(); first++) {
                for (int second = first; second < VARIANTS.size(); second++) {
                    builder.registerExpression(locus, pair(locus, VARIANTS.get(first), VARIANTS.get(second)),
                            ContentId.parse("fixture:expression/" + locus.name().toLowerCase(Locale.ROOT) + "/" + first + "_" + second));
                }
            }
        }
        return builder.freeze();
    }

    private static Genome genome() {
        var loci = new EnumMap<Locus, AllelePair>(Locus.class);
        for (Locus locus : Locus.values()) loci.put(locus, pair(locus, "a", "a"));
        return new Genome(VERSION, loci);
    }

    private static Map<MutationCategory, Set<Locus>> allowed() {
        var result = new EnumMap<MutationCategory, Set<Locus>>(MutationCategory.class);
        result.put(COSMETIC, EnumSet.of(Locus.PIGMENT, Locus.COVER));
        result.put(PHYSIOLOGICAL, EnumSet.of(Locus.MUSCLE, Locus.STAMINA));
        result.put(BEHAVIORAL_SENSORY, EnumSet.of(Locus.SENSE, Locus.TEMPER));
        result.put(ARCANE, EnumSet.of(Locus.ARCANA));
        return result;
    }

    private static MutationDefinition definition(MutationCategory category, Impact impact, Locus locus) {
        String target = switch (impact) {
            case COSMETIC -> "m_cosmetic";
            case BENEFICIAL -> "m_good";
            case TRADEOFF -> "m_trade";
            case HARMFUL -> "m_bad";
        };
        return new MutationDefinition(ContentId.parse("fixture:mutation/" + locus.name().toLowerCase(Locale.ROOT)
                + "/" + target), category, locus, allele(locus, target), impact,
                Set.of(pair(locus, "a", "a"), pair(locus, "a", "b"), pair(locus, "b", "b"), pair(locus, "a", target)));
    }

    private static List<MutationDefinition> definitions() {
        var result = new ArrayList<MutationDefinition>();
        allowed().forEach((category, loci) -> {
            for (Locus locus : loci) {
                for (Impact impact : Impact.values()) {
                    if ((category == COSMETIC) == (impact == Impact.COSMETIC)) result.add(definition(category, impact, locus));
                }
            }
        });
        return result;
    }

    private static MutationRules rules(List<MutationDefinition> definitions) {
        return new MutationRules(MUTATIONS, GENETICS, allowed(), definitions);
    }

    private static MutationPolicy policy(MutationCategory category, double chance) {
        var chances = new EnumMap<MutationCategory, Double>(MutationCategory.class);
        for (MutationCategory value : MutationCategory.values()) chances.put(value, value == category ? chance : 0.0);
        return new MutationPolicy(chances, WEIGHTS, MutationPolicy.UnavailablePolicy.SKIP);
    }

    private static final class Scripted implements MutationRandom {
        private final ArrayDeque<Double> units = new ArrayDeque<>();
        private final ArrayDeque<Integer> indices = new ArrayDeque<>();
        private final List<Integer> bounds = new ArrayList<>();

        Scripted(double... units) {
            for (double unit : units) this.units.add(unit);
        }

        Scripted indices(int... indices) {
            for (int index : indices) this.indices.add(index);
            return this;
        }

        @Override
        public double nextUnit() {
            assertFalse(units.isEmpty(), "Unexpected extra probability draw (possible reroll)");
            return units.remove();
        }

        @Override
        public int nextIndex(int bound) {
            bounds.add(bound);
            assertFalse(indices.isEmpty(), "Unexpected extra index draw (possible reroll)");
            return indices.remove();
        }

        void exhausted() {
            assertTrue(units.isEmpty(), "Expected probability draw was not used");
            assertTrue(indices.isEmpty(), "Expected index draw was not used");
        }
    }

    @Test
    void defaultCategoryIntervalsAreExactIncludingTheSharedBoundaries() {
        var policy = MutationPolicy.gddDefaults();
        assertEquals(0, new BigDecimal("0.92").compareTo(policy.noMutationChance()));
        var rules = rules(definitions());
        assertEquals(MUTATIONS, rules.reference());
        assertEquals(VERSION, rules.geneticsReference());
        for (double draw : new double[]{0, Math.nextDown(0.92)}) {
            var random = new Scripted(draw);
            assertEquals(new Unchanged(BASELINE), rules.apply(BASELINE, policy, random));
            random.exhausted();
        }
        double[] draws = {0.92, Math.nextDown(0.96), 0.96, Math.nextDown(0.985),
                0.985, Math.nextDown(0.995), 0.995, Math.nextDown(1.0)};
        MutationCategory[] expected = {COSMETIC, COSMETIC, PHYSIOLOGICAL, PHYSIOLOGICAL,
                BEHAVIORAL_SENSORY, BEHAVIORAL_SENSORY, ARCANE, ARCANE};
        for (int index = 0; index < draws.length; index++) {
            var random = expected[index] == COSMETIC ? new Scripted(draws[index]) : new Scripted(draws[index], 0);
            random.indices(0, 0, 0);
            var changed = assertInstanceOf(Mutated.class, rules.apply(BASELINE, policy, random));
            assertEquals(expected[index], changed.change().category());
            random.exhausted();
        }
    }

    @Test
    void impactIntervalsUseProvisionalThirtyFortyThirtyWeights() {
        double[] draws = {0, Math.nextDown(0.3), 0.3, Math.nextDown(0.7), 0.7, Math.nextDown(1.0)};
        Impact[] expected = {BENEFICIAL, BENEFICIAL, TRADEOFF, TRADEOFF, HARMFUL, HARMFUL};
        var rules = rules(definitions());
        for (int index = 0; index < draws.length; index++) {
            var random = new Scripted(0, draws[index]).indices(0, 0, 0);
            assertEquals(expected[index], assertInstanceOf(Mutated.class,
                    rules.apply(BASELINE, policy(PHYSIOLOGICAL, 1), random)).change().impact());
            random.exhausted();
        }
    }

    @Test
    void changesExactlyOneSelectedCanonicalCopyAndPreservesTheInheritedGenome() {
        var loci = new EnumMap<>(BASELINE.loci());
        loci.put(Locus.PIGMENT, pair(Locus.PIGMENT, "a", "b"));
        var inherited = new Genome(VERSION, loci);
        var random = new Scripted(0).indices(0, 0, 1);
        var changed = assertInstanceOf(Mutated.class, rules(definitions()).apply(inherited, policy(COSMETIC, 1), random));
        assertEquals(List.of(2, 1, 2), random.bounds);
        assertEquals(1, changed.change().copyIndex());
        assertEquals(allele(Locus.PIGMENT, "b"), changed.change().previousAllele());
        assertEquals(allele(Locus.PIGMENT, "m_cosmetic"), changed.change().newAllele());
        assertEquals(pair(Locus.PIGMENT, "a", "m_cosmetic"), changed.genome().loci().get(Locus.PIGMENT));
        assertEquals(loci, inherited.loci());
        for (Locus locus : Locus.values()) {
            if (locus != Locus.PIGMENT) assertEquals(inherited.loci().get(locus), changed.genome().loci().get(locus));
        }
        random.exhausted();
        var replaceFirst = assertInstanceOf(Mutated.class, rules(definitions()).apply(inherited,
                policy(COSMETIC, 1), new Scripted(0).indices(0, 0, 0)));
        // Canonical sorting moves the unchanged b copy to position 0; provenance refers to the input.
        assertEquals(pair(Locus.PIGMENT, "b", "m_cosmetic"), replaceFirst.genome().loci().get(Locus.PIGMENT));
        assertEquals(0, replaceFirst.change().copyIndex());
        assertEquals(allele(Locus.PIGMENT, "a"), replaceFirst.change().previousAllele());
    }

    @Test
    void anExistingTargetCopyIsNeverSelectedAsANoOpMutation() {
        var loci = new EnumMap<>(BASELINE.loci());
        loci.put(Locus.MUSCLE, pair(Locus.MUSCLE, "a", "m_good"));
        var inherited = new Genome(VERSION, loci);
        var random = new Scripted(0, 0).indices(0, 0, 0);
        var changed = assertInstanceOf(Mutated.class, rules(definitions()).apply(inherited, policy(PHYSIOLOGICAL, 1), random));
        assertEquals(List.of(2, 1, 1), random.bounds);
        assertEquals(0, changed.change().copyIndex());
        assertEquals(pair(Locus.MUSCLE, "m_good", "m_good"), changed.genome().loci().get(Locus.MUSCLE));
        random.exhausted();
    }

    @Test
    void absentCategoryIsSkippedWithoutChangingOtherCategoryProbabilitiesOrRerolling() {
        var random = new Scripted(0.995, 0.5);
        var withoutArcane = definitions().stream().filter(definition -> definition.category() != ARCANE).toList();
        var result = rules(withoutArcane).apply(BASELINE, MutationPolicy.gddDefaults(), random);
        assertEquals(new Skipped(BASELINE, ARCANE, TRADEOFF, SkipReason.CATEGORY_UNAVAILABLE), result);
        assertSame(BASELINE, result.genome());
        assertTrue(random.bounds.isEmpty());
        random.exhausted();
    }

    @Test
    void unavailableImpactIsSkippedRatherThanReweightedToAnAvailableImpact() {
        var random = new Scripted(0, 0.8);
        var result = rules(List.of(definition(PHYSIOLOGICAL, BENEFICIAL, Locus.MUSCLE)))
                .apply(BASELINE, policy(PHYSIOLOGICAL, 1), random);
        assertEquals(new Skipped(BASELINE, PHYSIOLOGICAL, HARMFUL, SkipReason.IMPACT_UNAVAILABLE), result);
        random.exhausted();
    }

    @Test
    void explicitSourcePairWhitelistCanSkipAnOtherwiseRegisteredTarget() {
        var original = definition(PHYSIOLOGICAL, BENEFICIAL, Locus.MUSCLE);
        var onlyBB = new MutationDefinition(original.id(), original.category(), original.locus(), original.targetAllele(),
                original.impact(), Set.of(pair(Locus.MUSCLE, "b", "b")));
        var random = new Scripted(0, 0);
        assertEquals(new Skipped(BASELINE, PHYSIOLOGICAL, BENEFICIAL, SkipReason.GENOTYPE_UNSUPPORTED),
                rules(List.of(onlyBB)).apply(BASELINE, policy(PHYSIOLOGICAL, 1), random));
        random.exhausted();
    }

    @Test
    void invalidGenomesAreRejectedBeforeTheFirstRandomDraw() {
        var random = new Scripted();
        var rules = rules(definitions());
        assertThrows(IllegalArgumentException.class, () -> rules.apply(new Genome(
                new RulesetVersion(VERSION.id(), 2), BASELINE.loci()), MutationPolicy.gddDefaults(), random));
        var loci = new EnumMap<>(BASELINE.loci());
        loci.put(Locus.HEAD, pair(Locus.FRAME, "a", "b"));
        assertThrows(IllegalArgumentException.class,
                () -> rules.apply(new Genome(VERSION, loci), MutationPolicy.gddDefaults(), random));
        loci.put(Locus.HEAD, pair(Locus.HEAD, "a", "missing"));
        assertThrows(IllegalArgumentException.class,
                () -> rules.apply(new Genome(VERSION, loci), MutationPolicy.gddDefaults(), random));
        random.exhausted();
    }

    @Test
    void alreadyInheritedMutantAllelesAreNotCappedOrCountedAsDeNovo() {
        var loci = new EnumMap<>(BASELINE.loci());
        loci.put(Locus.MUSCLE, pair(Locus.MUSCLE, "m_good", "m_bad"));
        loci.put(Locus.PIGMENT, pair(Locus.PIGMENT, "m_cosmetic", "m_cosmetic"));
        var mutantParent = new Genome(VERSION, loci);
        var child = MendelianInheritance.inherit(mutantParent, mutantParent, GENETICS, () -> false);
        assertEquals(pair(Locus.MUSCLE, "m_bad", "m_bad"), child.loci().get(Locus.MUSCLE));
        assertEquals(pair(Locus.PIGMENT, "m_cosmetic", "m_cosmetic"), child.loci().get(Locus.PIGMENT));
        var random = new Scripted(0);
        assertEquals(new Unchanged(child), rules(definitions()).apply(child, policy(COSMETIC, 0), random));
        random.exhausted();
        var thirdMutation = assertInstanceOf(Mutated.class, rules(definitions()).apply(child,
                policy(PHYSIOLOGICAL, 1), new Scripted(0, 0).indices(0, 0, 0)));
        assertEquals(Locus.STAMINA, thirdMutation.change().locus());
        assertEquals(child.loci().get(Locus.MUSCLE), thirdMutation.genome().loci().get(Locus.MUSCLE));
        assertEquals(child.loci().get(Locus.PIGMENT), thirdMutation.genome().loci().get(Locus.PIGMENT));
        var changed = assertInstanceOf(Mutated.class, rules(definitions()).apply(BASELINE,
                policy(PHYSIOLOGICAL, 1), new Scripted(0, 0).indices(0, 0, 0)));
        var nextGeneration = MendelianInheritance.inherit(changed.genome(), BASELINE, GENETICS, () -> true);
        assertEquals(pair(Locus.MUSCLE, "a", "m_good"), nextGeneration.loci().get(Locus.MUSCLE));
        assertEquals(Map.of(pair(Locus.MUSCLE, "a", "a"), 0.25,
                pair(Locus.MUSCLE, "a", "m_good"), 0.5, pair(Locus.MUSCLE, "m_good", "m_good"), 0.25),
                MendelianInheritance.distribution(nextGeneration.loci().get(Locus.MUSCLE), nextGeneration.loci().get(Locus.MUSCLE)));
    }

    @Test
    void configurationAndMutationDefinitionsAreValidatedBeforePublication() {
        var good = definition(PHYSIOLOGICAL, BENEFICIAL, Locus.MUSCLE);
        assertThrows(IllegalArgumentException.class, () -> rules(List.of(good, good)));
        var alias = new MutationDefinition(ContentId.parse("fixture:alias"), good.category(), good.locus(),
                good.targetAllele(), good.impact(), good.fromPairs());
        assertThrows(IllegalArgumentException.class, () -> rules(List.of(good, alias)));
        var unknown = new MutationDefinition(good.id(), good.category(), good.locus(),
                allele(Locus.MUSCLE, "missing"), good.impact(), good.fromPairs());
        assertThrows(IllegalArgumentException.class, () -> rules(List.of(unknown)));
        var wrongLocus = new MutationDefinition(good.id(), good.category(), good.locus(),
                allele(Locus.FRAME, "m_good"), good.impact(), good.fromPairs());
        assertThrows(IllegalArgumentException.class, () -> rules(List.of(wrongLocus)));
        var wrongSource = new MutationDefinition(good.id(), good.category(), good.locus(), good.targetAllele(),
                good.impact(), Set.of(pair(Locus.HEAD, "a", "b")));
        assertThrows(IllegalArgumentException.class, () -> rules(List.of(wrongSource)));
        var allowlist = allowed();
        allowlist.remove(ARCANE);
        assertThrows(IllegalArgumentException.class, () -> new MutationRules(MUTATIONS, GENETICS, allowlist, List.of()));
        allowlist.put(ARCANE, Set.of(Locus.ARCANA));
        allowlist.put(PHYSIOLOGICAL, Set.of());
        assertThrows(IllegalArgumentException.class, () -> new MutationRules(MUTATIONS, GENETICS, allowlist, List.of(good)));
        assertThrows(IllegalArgumentException.class, () -> new MutationDefinition(good.id(), COSMETIC, good.locus(),
                good.targetAllele(), BENEFICIAL, good.fromPairs()));
        assertThrows(IllegalArgumentException.class, () -> new MutationDefinition(good.id(), PHYSIOLOGICAL, good.locus(),
                good.targetAllele(), Impact.COSMETIC, good.fromPairs()));
        assertThrows(IllegalArgumentException.class, () -> new MutationDefinition(good.id(), good.category(), good.locus(),
                good.targetAllele(), good.impact(), Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new MutationDefinition(good.id(), good.category(), good.locus(),
                good.targetAllele(), good.impact(), Set.of(pair(Locus.MUSCLE, "m_good", "m_good"))));
    }

    @Test
    void ratesAndWeightsRejectNonFiniteNegativeIncompleteAndOversubscribedInputs() {
        for (double value : new double[]{-0.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, 1.01}) {
            assertThrows(IllegalArgumentException.class, () -> policy(COSMETIC, value));
            var weights = new EnumMap<>(WEIGHTS);
            weights.put(BENEFICIAL, value);
            assertThrows(IllegalArgumentException.class, () -> new MutationPolicy(
                    MutationPolicy.gddDefaults().categoryChances(), weights, MutationPolicy.UnavailablePolicy.SKIP));
        }
        var chances = new EnumMap<>(MutationPolicy.gddDefaults().categoryChances());
        chances.put(COSMETIC, 1.0);
        assertThrows(IllegalArgumentException.class, () -> new MutationPolicy(chances, WEIGHTS, MutationPolicy.UnavailablePolicy.SKIP));
        chances.remove(COSMETIC);
        assertThrows(IllegalArgumentException.class, () -> new MutationPolicy(chances, WEIGHTS, MutationPolicy.UnavailablePolicy.SKIP));
        assertThrows(IllegalArgumentException.class, () -> new MutationPolicy(policy(COSMETIC, 1).categoryChances(),
                Map.of(BENEFICIAL, 0.3, TRADEOFF, 0.4, HARMFUL, 0.2), MutationPolicy.UnavailablePolicy.SKIP));
        assertThrows(IllegalArgumentException.class, () -> new MutationPolicy(policy(COSMETIC, 1).categoryChances(),
                Map.of(BENEFICIAL, 0.3, TRADEOFF, 0.4, HARMFUL, 0.3, Impact.COSMETIC, 0.0), MutationPolicy.UnavailablePolicy.SKIP));
    }

    @Test
    void immutableSnapshotsAndCanonicalRegistrationOrderPreserveSeededResults() {
        var sourceDefinitions = definitions();
        var allowlist = allowed();
        var first = new MutationRules(MUTATIONS, GENETICS, allowlist, sourceDefinitions);
        Collections.reverse(sourceDefinitions);
        var second = rules(sourceDefinitions);
        sourceDefinitions.clear();
        allowlist.values().forEach(Set::clear);
        allowlist.clear();
        var a = new SeededMutationRandom(1234);
        var b = new SeededMutationRandom(1234);
        for (int birth = 0; birth < 1_000; birth++) {
            assertEquals(first.apply(BASELINE, MutationPolicy.gddDefaults(), a), second.apply(BASELINE, MutationPolicy.gddDefaults(), b));
        }
        assertThrows(UnsupportedOperationException.class, () -> WEIGHTS.clear());
        var sourcePairs = new HashSet<>(definition(COSMETIC, Impact.COSMETIC, Locus.PIGMENT).fromPairs());
        var definition = new MutationDefinition(ContentId.parse("fixture:snapshot"), COSMETIC, Locus.PIGMENT,
                allele(Locus.PIGMENT, "m_cosmetic"), Impact.COSMETIC, sourcePairs);
        sourcePairs.clear();
        assertFalse(definition.fromPairs().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> definition.fromPairs().clear());
    }

    @Test
    void choosesLocusBeforeAlleleSoMoreTargetsDoNotBiasLocusSelection() {
        var original = definition(PHYSIOLOGICAL, BENEFICIAL, Locus.MUSCLE);
        var extra = new MutationDefinition(ContentId.parse("fixture:extra"), PHYSIOLOGICAL, Locus.MUSCLE,
                allele(Locus.MUSCLE, "m_trade"), BENEFICIAL, original.fromPairs());
        var rules = rules(List.of(original, extra, definition(PHYSIOLOGICAL, BENEFICIAL, Locus.STAMINA)));
        var random = new Scripted(0, 0).indices(1, 0, 0);
        var result = assertInstanceOf(Mutated.class, rules.apply(BASELINE, policy(PHYSIOLOGICAL, 1), random));
        assertEquals(Locus.STAMINA, result.change().locus());
        assertEquals(List.of(2, 1, 2), random.bounds);
        random.exhausted();
        var chooseSecondTarget = new Scripted(0, 0).indices(0, 1, 0);
        var another = assertInstanceOf(Mutated.class, rules.apply(BASELINE, policy(PHYSIOLOGICAL, 1), chooseSecondTarget));
        assertEquals(List.of(2, 2, 2), chooseSecondTarget.bounds);
        assertEquals(original.targetAllele(), another.change().newAllele());
        chooseSecondTarget.exhausted();
    }

    @Test
    void malformedInjectedRandomValuesFailWithoutMutatingTheInput() {
        var rules = rules(definitions());
        var policy = policy(COSMETIC, 1);
        for (double value : new double[]{-0.1, 1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> rules.apply(BASELINE, policy, new Scripted(value)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> rules.apply(BASELINE, policy, new Scripted(0).indices(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> rules.apply(BASELINE, policy, new Scripted(0).indices(2)));
        assertThrows(IllegalArgumentException.class,
                () -> rules.apply(BASELINE, policy(PHYSIOLOGICAL, 1), new Scripted(0, 1)));
        assertEquals(genome(), BASELINE);
    }

    @Test
    void mutationStreamHasAnIndependentlyCalculatedSeed42Vector() {
        // Independently derived using the Java LCG equations, two nextDouble then three nextInt calls.
        var expected = List.of("TRADEOFF MUSCLE 0", "BENEFICIAL MUSCLE 0", "TRADEOFF STAMINA 1",
                "TRADEOFF STAMINA 1", "TRADEOFF MUSCLE 1", "BENEFICIAL MUSCLE 1", "TRADEOFF STAMINA 0",
                "BENEFICIAL MUSCLE 1", "HARMFUL MUSCLE 0", "TRADEOFF MUSCLE 0");
        var random = new SeededMutationRandom(42);
        var rules = rules(definitions());
        for (String entry : expected) {
            var change = assertInstanceOf(Mutated.class, rules.apply(BASELINE, policy(PHYSIOLOGICAL, 1), random)).change();
            assertEquals(entry, change.impact() + " " + change.locus() + " " + change.copyIndex());
        }
        assertEquals(ContentId.parse("genesis:mutation_java_random_lcg48_v1"), SeededMutationRandom.ALGORITHM);
    }

    @Test
    void oneHundredThousandBirthsMatchCategoryAndImpactWeightsAndChangeAtMostOneCopy() {
        int births = 100_000;
        int unchanged = 0;
        var categories = new EnumMap<MutationCategory, Integer>(MutationCategory.class);
        var impacts = new EnumMap<Impact, Integer>(Impact.class);
        var rules = rules(definitions());
        var policy = MutationPolicy.gddDefaults();
        var random = new SeededMutationRandom(0x4D55544154494F4EL);
        for (int birth = 0; birth < births; birth++) {
            var result = rules.apply(BASELINE, policy, random);
            if (result instanceof Unchanged) {
                unchanged++;
            } else {
                var changed = assertInstanceOf(Mutated.class, result);
                categories.merge(changed.change().category(), 1, Integer::sum);
                if (changed.change().impact() != Impact.COSMETIC) impacts.merge(changed.change().impact(), 1, Integer::sum);
                int changedLoci = 0;
                for (Locus locus : Locus.values()) {
                    if (!BASELINE.loci().get(locus).equals(changed.genome().loci().get(locus))) changedLoci++;
                }
                assertEquals(1, changedLoci);
                assertEquals(new AllelePair(allele(changed.change().locus(), "a"), changed.change().newAllele()),
                        changed.genome().loci().get(changed.change().locus()));
                assertEquals(12, GENETICS.resolveExpressions(changed.genome()).size());
            }
        }
        assertFrequency(unchanged, births, 0.92);
        for (MutationCategory category : MutationCategory.values()) {
            assertFrequency(categories.getOrDefault(category, 0), births, policy.categoryChances().get(category));
        }
        int nonCosmetic = impacts.values().stream().mapToInt(Integer::intValue).sum();
        for (Impact impact : new Impact[]{BENEFICIAL, TRADEOFF, HARMFUL}) {
            assertFrequency(impacts.getOrDefault(impact, 0), nonCosmetic, WEIGHTS.get(impact));
        }
    }

    private static void assertFrequency(int count, int trials, double probability) {
        double expected = trials * probability;
        double tolerance = 6 * Math.sqrt(trials * probability * (1 - probability));
        assertEquals(expected, count, tolerance, "Fixed-seed count differs by more than six binomial sigmas");
    }
}
