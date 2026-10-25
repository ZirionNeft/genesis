package dev.ifelse.zirion.genesis.core.content;

import dev.ifelse.zirion.genesis.core.breeding.*;
import dev.ifelse.zirion.genesis.core.code.*;
import dev.ifelse.zirion.genesis.core.genetics.*;
import dev.ifelse.zirion.genesis.core.lineage.*;
import dev.ifelse.zirion.genesis.core.morphology.MorphologyRules;
import dev.ifelse.zirion.genesis.core.mutation.*;
import dev.ifelse.zirion.genesis.core.phenotype.Stat;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static dev.ifelse.zirion.genesis.core.content.CanidBreedingContent.*;
import static dev.ifelse.zirion.genesis.core.content.CanidStarterContent.FAMILY;
import static dev.ifelse.zirion.genesis.core.content.CanidStarterContent.RIG;
import static dev.ifelse.zirion.genesis.core.phenotype.Stat.*;
import static org.junit.jupiter.api.Assertions.*;

class CanidBreedingContentTest {
    private static final Definitions CONTENT = create();
    private static final GeneticsRuleset GENETICS = CONTENT.catalog().rulesets().get(RULESET.id());
    private static final Genome WOLF = CONTENT.catalog().species().get(CanidStarterContent.WOLF).starterGenome();
    private static final Genome FOX = CONTENT.catalog().species().get(CanidStarterContent.FOX).starterGenome();

    private static Genome replace(Genome source, Locus locus, AllelePair pair) {
        var loci = new EnumMap<>(source.loci());
        loci.put(locus, pair);
        return new Genome(source.ruleset(), loci);
    }

    private static Genome replace(Genome source, Locus locus, String a, String b) {
        return replace(source, locus, new AllelePair(allele(locus, a), allele(locus, b)));
    }

    private static List<AllelePair> pairs(GeneticsRuleset genetics, Locus locus) {
        var alleles = genetics.alleles().values().stream().filter(allele -> allele.locus() == locus)
                .map(AlleleDefinition::id).sorted().toList();
        var result = new ArrayList<AllelePair>();
        for (int a = 0; a < alleles.size(); a++) {
            for (int b = a; b < alleles.size(); b++) result.add(new AllelePair(alleles.get(a), alleles.get(b)));
        }
        return result;
    }

    private static MutationPolicy policy(MutationCategory category) {
        var probabilities = new EnumMap<MutationCategory, Double>(MutationCategory.class);
        for (var value : MutationCategory.values()) probabilities.put(value, value == category ? 1.0 : 0.0);
        return new MutationPolicy(probabilities, MutationPolicy.gddDefaults().impactChances(), MutationPolicy.UnavailablePolicy.SKIP);
    }

    private static final class Draws implements MutationRandom {
        private final double impact;
        private int units;
        private int indices;
        Draws(double impact) { this.impact = impact; }
        public double nextUnit() { return units++ == 0 ? 0 : impact; }
        public int nextIndex(int bound) { indices++; return 0; }
    }

    @Test
    void v2IsSeparateFromTheUnchangedV1AndRetainsEveryOriginalPairAndStarterAllele() {
        var original = CanidStarterContent.create();
        var old = original.rulesets().get(CanidStarterContent.RULESET.id());
        assertEquals(1, original.version());
        assertEquals(24, old.alleles().size());
        assertEquals(2, CONTENT.catalog().version());
        assertEquals(2, CONTENT.catalog().families().get(FAMILY).version());
        assertEquals(1, CONTENT.catalog().rigs().get(RIG).version());
        assertEquals(30, GENETICS.alleles().size());
        assertEquals(41, java.util.Arrays.stream(Locus.values()).mapToInt(locus -> GENETICS.expressionOptions(locus).size()).sum());
        Genome oldWolf = original.species().get(CanidStarterContent.WOLF).starterGenome();
        int checked = 0;
        for (Locus locus : Locus.values()) {
            for (var pair : pairs(old, locus)) {
                assertEquals(old.resolveExpressions(replace(oldWolf, locus, pair)).get(locus),
                        GENETICS.resolveExpressions(replace(WOLF, locus, pair)).get(locus));
                checked++;
            }
        }
        assertEquals(36, checked);
        original.species().forEach((id, species) -> {
            var revised = CONTENT.catalog().species().get(id);
            assertEquals(species.starterGenome().loci(), revised.starterGenome().loci());
            assertEquals(2, revised.version());
            assertEquals(RULESET, revised.starterGenome().ruleset());
        });
        assertThrows(IllegalArgumentException.class, () -> GENETICS.validate(oldWolf));
        assertThrows(IllegalArgumentException.class, () -> old.validate(WOLF));
        assertEquals(WOLF, create().catalog().species().get(CanidStarterContent.WOLF).starterGenome());
        assertThrows(UnsupportedOperationException.class, () -> CONTENT.phenotypes().clear());
    }

    @Test
    void allFiftyFourPairsHaveExplicitExpressionAndFinitePhenotypeInEachArchetype() {
        int checked = 0;
        double[] lower = {0.70, 0.70, 0.70, 0.75, 0.70, 0.70, 0.60, 0.70, 0.60, 0.70};
        double[] upper = {1.45, 1.35, 1.40, 1.30, 1.50, 1.40, 1.55, 1.45, 1.40, 1.80};
        double[][] bases = {{65, 70, 50, 75, 65, 60, 15, 70, 70, 50},
                {35, 40, 35, 90, 55, 85, 5, 95, 50, 45}, {50, 55, 42.5, 82.5, 60, 72.5, 10, 82.5, 60, 47.5}};
        for (Locus locus : Locus.values()) {
            for (AllelePair pair : pairs(GENETICS, locus)) {
                Genome candidate = replace(WOLF, locus, pair);
                assertEquals(12, GENETICS.resolveExpressions(candidate).size());
                for (Archetype archetype : Archetype.values()) {
                    var rules = CONTENT.phenotypes().get(archetype);
                    assertEquals(RULESET, rules.geneticsReference());
                    var values = rules.resolve(candidate).potential().values();
                    for (Stat stat : Stat.values()) {
                        double value = values.get(stat);
                        double base = bases[archetype.ordinal()][stat.ordinal()];
                        assertTrue(Double.isFinite(value));
                        assertTrue(value >= base * lower[stat.ordinal()] - 1e-10);
                        assertTrue(value <= base * upper[stat.ordinal()] + 1e-10);
                    }
                }
                checked++;
            }
        }
        assertEquals(54, checked);
    }

    @Test
    void wolfAndFoxHaveFixedNumericalVectorsWithCostsCountedOnce() {
        double[] wolf = {72.8, 82.6, 54, 66.75, 63.7, 56.4, 20.25, 77, 78.4, 62};
        double[] fox = {33.25, 38.8, 35, 99, 67.65, 93.5, 4.25, 102.6, 47, 56.7};
        for (Stat stat : Stat.values()) {
            assertEquals(wolf[stat.ordinal()], CONTENT.phenotypes().get(Archetype.WOLF).resolve(WOLF).potential().values().get(stat), 1e-10, stat.name());
            assertEquals(fox[stat.ordinal()], CONTENT.phenotypes().get(Archetype.FOX).resolve(FOX).potential().values().get(stat), 1e-10, stat.name());
        }
        for (var rules : CONTENT.phenotypes().values()) {
            assertTrue(rules.resolve(WOLF).activeAbility().isEmpty());
            assertTrue(rules.resolve(FOX).activeAbility().isEmpty());
        }
    }

    @Test
    void recessiveCosmeticsAndFragilityRemainHiddenInCarriersAndCanExpressInTheNextGeneration() {
        for (var entry : Map.of(Locus.PIGMENT, "shadow", Locus.LIMBS, "fragile_bones").entrySet()) {
            Locus locus = entry.getKey();
            ContentId mutant = allele(locus, entry.getValue());
            var base = WOLF.loci().get(locus).first();
            var carrier = replace(WOLF, locus, new AllelePair(base, mutant));
            assertEquals(GENETICS.resolveExpressions(WOLF).get(locus), GENETICS.resolveExpressions(carrier).get(locus));
            assertEquals(0.25, MendelianInheritance.distribution(carrier.loci().get(locus), carrier.loci().get(locus)).get(new AllelePair(mutant, mutant)));
            var expressed = replace(WOLF, locus, new AllelePair(mutant, mutant));
            assertEquals(expression(locus, entry.getValue()), GENETICS.resolveExpressions(expressed).get(locus));
            var rules = CONTENT.phenotypes().get(Archetype.WOLF);
            if (locus == Locus.PIGMENT) assertEquals(rules.resolve(WOLF), rules.resolve(expressed));
            else assertTrue(rules.resolve(expressed).potential().values().get(HEALTH) < rules.resolve(WOLF).potential().values().get(HEALTH));
        }
    }

    @Test
    void allSixMutationTargetsAreReachableFromStartersAndReplaceOneCopyOnly() {
        Object[][] cases = {{MutationCategory.COSMETIC, 0.0, Locus.PIGMENT, "shadow"},
                {MutationCategory.PHYSIOLOGICAL, 0.0, Locus.STAMINA, "developed_lungs"},
                {MutationCategory.PHYSIOLOGICAL, 0.3, Locus.MUSCLE, "hypertrophy"},
                {MutationCategory.PHYSIOLOGICAL, 0.7, Locus.LIMBS, "fragile_bones"},
                {MutationCategory.BEHAVIORAL_SENSORY, 0.7, Locus.TEMPER, "nervous"},
                {MutationCategory.ARCANE, 0.3, Locus.ARCANA, "regeneration"}};
        for (Genome parent : List.of(WOLF, FOX)) {
            for (Object[] test : cases) {
                var category = (MutationCategory) test[0];
                var random = new Draws((double) test[1]);
                var mutated = assertInstanceOf(MutationRules.Mutated.class, CONTENT.mutations().apply(parent, policy(category), random));
                assertEquals(allele((Locus) test[2], (String) test[3]), mutated.change().newAllele());
                assertEquals(category, mutated.change().category());
                assertEquals(3, random.indices);
                assertEquals(category == MutationCategory.COSMETIC ? 1 : 2, random.units);
                int changes = 0;
                for (Locus locus : Locus.values()) {
                    if (!parent.loci().get(locus).equals(mutated.genome().loci().get(locus))) changes++;
                }
                assertEquals(1, changes);
                assertEquals(new AllelePair(parent.loci().get(mutated.change().locus()).second(), mutated.change().newAllele()),
                        mutated.genome().loci().get(mutated.change().locus()));
                for (var rules : CONTENT.phenotypes().values()) assertNotNull(rules.resolve(mutated.genome()));
            }
        }
    }

    @Test
    void sparseCategoriesSkipUnsupportedImpactsWithoutRenormalization() {
        for (var category : List.of(MutationCategory.BEHAVIORAL_SENSORY, MutationCategory.ARCANE)) {
            var random = new Draws(0); // BENEFICIAL is absent in both categories.
            var skipped = assertInstanceOf(MutationRules.Skipped.class, CONTENT.mutations().apply(WOLF, policy(category), random));
            assertEquals(MutationRules.SkipReason.IMPACT_UNAVAILABLE, skipped.reason());
            assertEquals(WOLF, skipped.genome());
            assertEquals(2, random.units);
            assertEquals(0, random.indices);
        }
    }

    @Test
    void hypertrophyLungsAndRegenerationHaveExplicitCostsAndInteractions() {
        var rules = CONTENT.phenotypes().get(Archetype.WOLF);
        var regular = replace(WOLF, Locus.MUSCLE, "normal", "normal");
        var bulky = replace(regular, Locus.MUSCLE, "hypertrophy", "hypertrophy");
        var before = rules.resolve(regular).potential().values();
        var after = rules.resolve(bulky).potential().values();
        assertTrue(after.get(STRENGTH) > before.get(STRENGTH));
        assertTrue(after.get(SPEED) < before.get(SPEED));
        assertTrue(after.get(STAMINA) < before.get(STAMINA));
        assertTrue(after.get(METABOLISM) > before.get(METABOLISM));
        // Large body + full hypertrophy: explicit +0.05 strength and -0.10 stamina, in addition to single-locus effects.
        assertEquals(70 * 1.33, after.get(STRENGTH), 1e-10);
        assertEquals(65 * 0.80, after.get(STAMINA), 1e-10);
        var lungs = rules.resolve(replace(regular, Locus.STAMINA, "normal", "developed_lungs")).potential().values();
        assertTrue(lungs.get(STAMINA) > before.get(STAMINA));
        assertTrue(lungs.get(STRENGTH) < before.get(STRENGTH));
        var regen = replace(regular, Locus.ARCANA, "dormant", "regeneration");
        var fast = replace(regen, Locus.METABOLISM, "fast", "fast");
        assertEquals(50 * 0.35, rules.resolve(fast).potential().values().get(METABOLISM)
                - rules.resolve(regen).potential().values().get(METABOLISM), 1e-10); // +0.20 fast +0.15 interaction.
    }

    @Test
    void allSixArcanaPairsHaveAtMostOneAbilityAndTheMixedRarePairKeepsRegenerationHidden() {
        var rules = CONTENT.phenotypes().get(Archetype.HYBRID);
        assertEquals(6, pairs(GENETICS, Locus.ARCANA).size());
        for (var pair : pairs(GENETICS, Locus.ARCANA)) {
            var result = rules.resolve(replace(WOLF, Locus.ARCANA, pair));
            String expressed = GENETICS.resolveExpressions(replace(WOLF, Locus.ARCANA, pair)).get(Locus.ARCANA).path();
            if (expressed.endsWith("/dormant")) assertTrue(result.activeAbility().isEmpty());
            else assertEquals(ContentId.parse("genesis:ability/" + (expressed.endsWith("/regeneration") ? "regeneration" : "shadow_dash")), result.activeAbility().orElseThrow());
        }
        var mixed = replace(WOLF, Locus.ARCANA, "regeneration", "shadow_affinity");
        assertEquals(expression(Locus.ARCANA, "shadow_affinity"), GENETICS.resolveExpressions(mixed).get(Locus.ARCANA));
        assertTrue(mixed.loci().get(Locus.ARCANA).first().equals(allele(Locus.ARCANA, "regeneration")));
    }

    @Test
    void fullStrengthAndFullSpeedCannotBeCombinedAcrossAnyRelevantPairs() {
        var rules = CONTENT.phenotypes().get(Archetype.WOLF);
        int atStrengthCap = 0;
        for (var frame : pairs(GENETICS, Locus.FRAME)) {
            for (var limbs : pairs(GENETICS, Locus.LIMBS)) {
                for (var muscle : pairs(GENETICS, Locus.MUSCLE)) {
                    for (var stamina : pairs(GENETICS, Locus.STAMINA)) {
                        for (var cover : pairs(GENETICS, Locus.COVER)) {
                            var genome = replace(replace(replace(replace(replace(WOLF, Locus.FRAME, frame), Locus.LIMBS, limbs),
                                    Locus.MUSCLE, muscle), Locus.STAMINA, stamina), Locus.COVER, cover);
                            var values = rules.resolve(genome).potential().values();
                            if (values.get(STRENGTH) >= 70 * 1.35) {
                                atStrengthCap++;
                                assertTrue(values.get(SPEED) < 75, "Capped strength must retain its movement cost");
                            }
                        }
                    }
                }
            }
        }
        assertTrue(atStrengthCap > 0);
    }

    @Test
    void morphologyAuditReportsEveryMissingPlanAndCannotBecomeASpawnReadyProfile() {
        var morphology = CONTENT.unmappedMorphology();
        assertEquals(new BigInteger("864"), morphology.coverage().required());
        assertEquals(0, morphology.coverage().registered());
        assertFalse(morphology.coverage().complete());
        assertInstanceOf(MorphologyRules.Unsupported.class, morphology.resolve(WOLF));
        assertInstanceOf(MorphologyRules.Unsupported.class, morphology.resolve(FOX));
        assertThrows(IllegalArgumentException.class, () -> new ReproductionProfile(
                new RulesetVersion(ContentId.parse("genesis:canid_hybrid_draft"), 1), new RulesetVersion(FAMILY, 2),
                new RulesetVersion(RIG, 1), new RulesetVersion(ContentId.parse("genesis:canid_assets_draft"), 1),
                CONTENT.phenotypes().get(Archetype.HYBRID), morphology, Set.of(), Set.of()));
    }

    @Test
    void revisedContentWorksWithTheSeededWorkflowAndExistingGen1SchemaWithoutSilentMigration() {
        UUID a = new UUID(0, 1);
        UUID b = new UUID(0, 2);
        var input = new BreedingWorkflow.Request(
                new BreedingWorkflow.Parent(a, BreedingParent.starter(CONTENT.catalog().species().get(CanidStarterContent.WOLF)), SpeciesAncestry.original(CanidStarterContent.WOLF), List.of()),
                new BreedingWorkflow.Parent(b, BreedingParent.starter(CONTENT.catalog().species().get(CanidStarterContent.FOX)), SpeciesAncestry.original(CanidStarterContent.FOX), List.of()),
                new Pedigree(List.of(PedigreeEntry.founder(a), PedigreeEntry.founder(b))), 4,
                new BreedingWorkflow.Conditions(1, 1, 1, 1), new GenerationSeed(42));
        var workflow = new BreedingWorkflow(CONTENT.catalog(), CONTENT.mutations(), Set.of());
        var settings = new BreedingWorkflow.Policy(CompatibilityPolicy.gddDefaults(), InbreedingPolicy.softDefaults(), MutationPolicy.gddDefaults());
        var conceived = assertInstanceOf(BreedingWorkflow.Conceived.class, workflow.calculate(input, settings));
        assertEquals(conceived, workflow.calculate(input, settings));
        assertEquals(RULESET, conceived.genome().ruleset());
        var blueprint = new GenesisBlueprint(conceived.genome(), new RulesetVersion(FAMILY, 2), new RulesetVersion(RIG, 1),
                new RulesetVersion(ContentId.parse("genesis:canid_hybrid_draft"), 1),
                new RulesetVersion(ContentId.parse("genesis:canid_assets_draft"), 1), conceived.cosmeticSeed(), List.of());
        assertEquals(blueprint, GenesisCodeCodec.decode(GenesisCodeCodec.encode(blueprint)));
        assertThrows(IllegalArgumentException.class, () -> CanidStarterContent.create().rulesets().get(RULESET.id()).validate(conceived.genome()));
    }

    @Test
    void oneHundredThousandMutationStepsExposeTheActualSevenPercentContentAvailability() {
        var random = new SeededMutationRandom(0x43414E4944535632L);
        var counts = new EnumMap<MutationCategory, Integer>(MutationCategory.class);
        int unchanged = 0;
        int skipped = 0;
        for (int index = 0; index < 100_000; index++) {
            var result = CONTENT.mutations().apply(WOLF, MutationPolicy.gddDefaults(), random);
            if (result instanceof MutationRules.Unchanged) unchanged++;
            else if (result instanceof MutationRules.Skipped failure) {
                assertEquals(MutationRules.SkipReason.IMPACT_UNAVAILABLE, failure.reason());
                skipped++;
            } else {
                var mutated = assertInstanceOf(MutationRules.Mutated.class, result);
                counts.merge(mutated.change().category(), 1, Integer::sum);
                GENETICS.validate(mutated.genome());
            }
        }
        frequency(unchanged, 0.92);
        frequency(skipped, 0.01);
        frequency(counts.getOrDefault(MutationCategory.COSMETIC, 0), 0.04);
        frequency(counts.getOrDefault(MutationCategory.PHYSIOLOGICAL, 0), 0.025);
        frequency(counts.getOrDefault(MutationCategory.BEHAVIORAL_SENSORY, 0), 0.003);
        frequency(counts.getOrDefault(MutationCategory.ARCANE, 0), 0.002);
    }

    private static void frequency(int actual, double expected) {
        assertEquals(100_000 * expected, actual, 6 * Math.sqrt(100_000 * expected * (1 - expected)));
    }
}
