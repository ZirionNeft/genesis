package dev.ifelse.zirion.genesis.core.content;

import dev.ifelse.zirion.genesis.core.breeding.BreedingChance;
import dev.ifelse.zirion.genesis.core.breeding.BreedingCompatibility;
import dev.ifelse.zirion.genesis.core.breeding.BreedingParent;
import dev.ifelse.zirion.genesis.core.breeding.CompatibilityPolicy;
import dev.ifelse.zirion.genesis.core.breeding.MendelianInheritance;
import dev.ifelse.zirion.genesis.core.breeding.SeededAlleleRandom;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.Locus;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static dev.ifelse.zirion.genesis.core.content.CanidStarterContent.*;
import static org.junit.jupiter.api.Assertions.*;

class CanidContentTest {
    private static final ContentId ALT_RIG = ContentId.parse("fixture:other_rig");
    private static final ContentId OTHER_FAMILY = ContentId.parse("fixture:other_family");
    private static final ContentId OTHER_SPECIES = ContentId.parse("fixture:other_species");
    private static final CompatibilityPolicy POLICY = CompatibilityPolicy.gddDefaults();

    private static GeneticsCatalog.Builder baseBuilder() {
        var starter = create();
        return GeneticsCatalog.builder(ContentId.parse("fixture:catalog"), 1)
                .registerRuleset("genesis", starter.rulesets().get(RULESET.id()))
                .registerRig("genesis", starter.rigs().get(RIG));
    }

    private static GeneticsCatalog.Builder fullBuilder() {
        var starter = create();
        var builder = baseBuilder();
        starter.families().values().forEach(value -> builder.registerFamily("genesis", value));
        starter.species().values().forEach(value -> builder.registerSpecies("genesis", value));
        return builder;
    }

    private static BreedingParent parent(GeneticsCatalog catalog, ContentId species) {
        return BreedingParent.starter(catalog.species().get(species));
    }

    private static ContentId id(String path) { return new ContentId("genesis", path); }

    private static void assertRejected(BreedingCompatibility.Reason reason, BreedingParent a,
                                       BreedingParent b, GeneticsCatalog catalog) {
        assertEquals(new BreedingCompatibility.Rejected(reason), BreedingCompatibility.evaluate(a, b, catalog, POLICY));
        assertEquals(new BreedingCompatibility.Rejected(reason), BreedingCompatibility.evaluate(b, a, catalog, POLICY));
    }

    @Test
    void draftContentIsClosedVersionedAndContainsOnlyTwoOriginalSpecies() {
        var catalog = create();
        assertEquals(1, catalog.version());
        assertEquals(1, catalog.rigs().size());
        assertEquals(1, catalog.families().size());
        assertEquals(Set.of(WOLF, FOX), catalog.species().keySet());
        var ruleset = catalog.rulesets().get(RULESET.id());
        assertEquals(RULESET, ruleset.reference());
        assertEquals(24, ruleset.alleles().size());
        for (var species : catalog.species().values()) {
            ruleset.validate(species.starterGenome());
            assertEquals(12, ruleset.resolveExpressions(species.starterGenome()).size());
            assertEquals(id("expression/arcana/dormant"),
                    ruleset.resolveExpressions(species.starterGenome()).get(Locus.ARCANA));
        }
    }

    @Test
    void frameExpressionsFollowTheGddExampleIncludingTheHeterozygote() {
        var catalog = create();
        var ruleset = catalog.rulesets().get(RULESET.id());
        var normal = id("allele/frame/normal");
        var large = id("allele/frame/large");
        String[] outcomes = {"normal", "large", "very_large"};
        AllelePair[] pairs = {new AllelePair(normal, normal), new AllelePair(normal, large), new AllelePair(large, large)};
        for (int index = 0; index < pairs.length; index++) {
            var loci = new EnumMap<>(catalog.species().get(WOLF).starterGenome().loci());
            loci.put(Locus.FRAME, pairs[index]);
            assertEquals(id("expression/frame/" + outcomes[index]),
                    ruleset.resolveExpressions(new Genome(RULESET, loci)).get(Locus.FRAME));
        }
    }

    @Test
    void wolfFoxAndTheirOffspringRemainValidWithoutReRollingGenomes() {
        var catalog = create();
        var ruleset = catalog.rulesets().get(RULESET.id());
        var wolf = parent(catalog, WOLF);
        var fox = parent(catalog, FOX);
        var random = new SeededAlleleRandom(12345);
        for (int index = 0; index < 1_000; index++) {
            var first = MendelianInheritance.inherit(wolf.genome(), fox.genome(), ruleset, random);
            var second = MendelianInheritance.inherit(wolf.genome(), fox.genome(), ruleset, random);
            assertEquals(id("expression/head/wolf_fox"), ruleset.resolveExpressions(first).get(Locus.HEAD));
            assertEquals(id("expression/appendage/bushy_tail"), ruleset.resolveExpressions(first).get(Locus.APPENDAGE));
            assertEquals(id("expression/pigment/grey_orange"), ruleset.resolveExpressions(first).get(Locus.PIGMENT));
            var hybridA = BreedingParent.hybrid(FAMILY, RIG, first);
            var hybridB = BreedingParent.hybrid(FAMILY, RIG, second);
            assertEquals(new BreedingCompatibility.Allowed(BreedingCompatibility.Level.SAME_FAMILY, 0.70),
                    BreedingCompatibility.evaluate(hybridA, hybridB, catalog, POLICY));
            var nextGeneration = MendelianInheritance.inherit(first, second, ruleset, random);
            ruleset.validate(nextGeneration);
            assertTrue(ruleset.resolveExpressions(nextGeneration).values().stream().allMatch(value -> value != null));
            assertEquals(first, new Genome(RULESET, first.loci()));
        }
    }

    @Test
    void originalSpeciesGetNinetyPercentAndCrossesGetSeventyBeforeReadiness() {
        var catalog = create();
        var wolf = parent(catalog, WOLF);
        var fox = parent(catalog, FOX);
        assertEquals(new BreedingCompatibility.Allowed(BreedingCompatibility.Level.SAME_ORIGINAL_SPECIES, 0.90),
                BreedingCompatibility.evaluate(wolf, wolf, catalog, POLICY));
        assertEquals(new BreedingCompatibility.Allowed(BreedingCompatibility.Level.SAME_ORIGINAL_SPECIES, 0.90),
                BreedingCompatibility.evaluate(fox, fox, catalog, POLICY));
        var cross = (BreedingCompatibility.Allowed) BreedingCompatibility.evaluate(wolf, fox, catalog, POLICY);
        assertEquals(BreedingCompatibility.Level.SAME_FAMILY, cross.level());
        assertEquals(0.70, cross.baseChance());
        assertEquals(cross, BreedingCompatibility.evaluate(fox, wolf, catalog, POLICY));
        assertEquals(0.8855, BreedingChance.calculate(cross.baseChance(), 1, 1, 1.1, 1.15), 1e-12);
    }

    @Test
    void identicalHybridGenomesDoNotMasqueradeAsTheSameOriginalSpecies() {
        var catalog = create();
        var wolf = parent(catalog, WOLF);
        var hybrid = BreedingParent.hybrid(FAMILY, RIG, wolf.genome());
        var expected = new BreedingCompatibility.Allowed(BreedingCompatibility.Level.SAME_FAMILY, 0.70);
        assertEquals(expected, BreedingCompatibility.evaluate(hybrid, hybrid, catalog, POLICY));
        assertEquals(expected, BreedingCompatibility.evaluate(hybrid, wolf, catalog, POLICY));
    }

    @Test
    void policyCanChangeChanceButCannotAuthorizeADifferentFamily() {
        var catalog = fullBuilder().registerFamily("fixture",
                new FamilyDefinition(OTHER_FAMILY, 1, Set.of(RIG), RULESET)).freeze();
        var wolf = parent(catalog, WOLF);
        var configured = new CompatibilityPolicy(0.8, 0.6);
        assertEquals(new BreedingCompatibility.Allowed(BreedingCompatibility.Level.SAME_ORIGINAL_SPECIES, 0.8),
                BreedingCompatibility.evaluate(wolf, wolf, catalog, configured));
        assertEquals(new BreedingCompatibility.Allowed(BreedingCompatibility.Level.SAME_FAMILY, 0.6),
                BreedingCompatibility.evaluate(wolf, parent(catalog, FOX), catalog, configured));
        var other = BreedingParent.hybrid(OTHER_FAMILY, RIG, wolf.genome());
        assertEquals(new BreedingCompatibility.Rejected(BreedingCompatibility.Reason.DIFFERENT_FAMILIES),
                BreedingCompatibility.evaluate(wolf, other, catalog, new CompatibilityPolicy(1, 1)));
        assertInstanceOf(BreedingCompatibility.Allowed.class,
                BreedingCompatibility.evaluate(wolf, wolf, catalog, new CompatibilityPolicy(0, 0)));
        for (double invalid : new double[]{-0.01, 1.01, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new CompatibilityPolicy(invalid, 0.7));
            assertThrows(IllegalArgumentException.class, () -> new CompatibilityPolicy(0.9, invalid));
        }
    }

    @Test
    void differentSupportedRigsStillRequireAnExplicitCrossRigMapping() {
        var starter = create();
        var builder = baseBuilder().registerRig("fixture", new RigDefinition(ALT_RIG, 1))
                .registerFamily("genesis", new FamilyDefinition(FAMILY, 1, Set.of(RIG, ALT_RIG), RULESET));
        starter.species().values().forEach(species -> builder.registerSpecies("genesis", species));
        var catalog = builder.freeze();
        var wolf = parent(catalog, WOLF);
        var otherRig = BreedingParent.hybrid(FAMILY, ALT_RIG, wolf.genome());
        assertRejected(BreedingCompatibility.Reason.INCOMPATIBLE_RIGS, wolf, otherRig, catalog);
    }

    @Test
    void unknownOrUnsupportedDefinitionsProduceExplicitRejections() {
        var catalog = fullBuilder().registerRig("fixture", new RigDefinition(ALT_RIG, 1)).freeze();
        var wolf = parent(catalog, WOLF);
        assertRejected(BreedingCompatibility.Reason.UNKNOWN_FAMILY, wolf,
                BreedingParent.hybrid(OTHER_FAMILY, RIG, wolf.genome()), catalog);
        assertRejected(BreedingCompatibility.Reason.UNKNOWN_RIG, wolf,
                BreedingParent.hybrid(FAMILY, ContentId.parse("fixture:missing"), wolf.genome()), catalog);
        assertRejected(BreedingCompatibility.Reason.UNSUPPORTED_RIG, wolf,
                BreedingParent.hybrid(FAMILY, ALT_RIG, wolf.genome()), catalog);
        assertRejected(BreedingCompatibility.Reason.UNKNOWN_SPECIES, wolf,
                new BreedingParent(FAMILY, RIG, Optional.of(OTHER_SPECIES), wolf.genome()), catalog);
    }

    @Test
    void originalSpeciesClaimsMustMatchRegisteredFamilyAndRig() {
        var starter = create();
        var builder = baseBuilder().registerRig("fixture", new RigDefinition(ALT_RIG, 1))
                .registerFamily("genesis", new FamilyDefinition(FAMILY, 1, Set.of(RIG, ALT_RIG), RULESET))
                .registerFamily("fixture", new FamilyDefinition(OTHER_FAMILY, 1, Set.of(RIG), RULESET))
                .registerSpecies("fixture", new SpeciesDefinition(OTHER_SPECIES, 1, FAMILY, ALT_RIG,
                        starter.species().get(WOLF).starterGenome()));
        starter.species().values().forEach(species -> builder.registerSpecies("genesis", species));
        var catalog = builder.freeze();
        var wolf = parent(catalog, WOLF);
        assertRejected(BreedingCompatibility.Reason.SPECIES_FAMILY_MISMATCH, wolf,
                new BreedingParent(OTHER_FAMILY, RIG, Optional.of(WOLF), wolf.genome()), catalog);
        assertRejected(BreedingCompatibility.Reason.SPECIES_RIG_MISMATCH, wolf,
                new BreedingParent(FAMILY, RIG, Optional.of(OTHER_SPECIES), wolf.genome()), catalog);
    }

    @Test
    void incompatibleGenomeVersionsUnknownAllelesAndWrongLociAreRejected() {
        var catalog = create();
        var wolf = parent(catalog, WOLF);
        var wrongVersion = new Genome(new RulesetVersion(RULESET.id(), 2), wolf.genome().loci());
        assertRejected(BreedingCompatibility.Reason.INVALID_GENOME, wolf,
                BreedingParent.hybrid(FAMILY, RIG, wrongVersion), catalog);
        var unknown = new EnumMap<>(wolf.genome().loci());
        unknown.put(Locus.ARCANA, new AllelePair(id("allele/arcana/dormant"), ContentId.parse("external:missing")));
        var invalid = new Genome(RULESET, unknown);
        assertRejected(BreedingCompatibility.Reason.INVALID_GENOME, wolf,
                BreedingParent.hybrid(FAMILY, RIG, invalid), catalog);
        assertEquals(ContentId.parse("external:missing"), invalid.loci().get(Locus.ARCANA).first());
        var misplaced = new EnumMap<>(wolf.genome().loci());
        misplaced.put(Locus.HEAD, wolf.genome().loci().get(Locus.FRAME));
        assertRejected(BreedingCompatibility.Reason.INVALID_GENOME, wolf,
                BreedingParent.hybrid(FAMILY, RIG, new Genome(RULESET, misplaced)), catalog);
    }

    @Test
    void catalogFreezeRejectsDanglingAndWrongVersionFamilyReferencesAndCanBeRepaired() {
        var starter = create();
        var builder = GeneticsCatalog.builder(ContentId.parse("fixture:catalog"), 1)
                .registerFamily("genesis", starter.families().get(FAMILY));
        assertThrows(IllegalStateException.class, builder::freeze);
        builder.registerRuleset("genesis", starter.rulesets().get(RULESET.id()));
        assertThrows(IllegalStateException.class, builder::freeze);
        builder.registerRig("genesis", starter.rigs().get(RIG));
        assertEquals(Set.of(FAMILY), builder.freeze().families().keySet());
        var badVersion = baseBuilder().registerFamily("genesis", new FamilyDefinition(FAMILY, 1,
                Set.of(RIG), new RulesetVersion(RULESET.id(), 2)));
        assertThrows(IllegalStateException.class, badVersion::freeze);
    }

    @Test
    void catalogFreezeRejectsSpeciesWithWrongFamilyRigOrGenome() {
        var starter = create();
        var wolf = starter.species().get(WOLF);
        var noFamily = baseBuilder().registerSpecies("genesis", wolf);
        assertThrows(IllegalStateException.class, noFamily::freeze);
        var unsupportedRig = baseBuilder().registerFamily("genesis", starter.families().get(FAMILY))
                .registerRig("fixture", new RigDefinition(ALT_RIG, 1))
                .registerSpecies("fixture", new SpeciesDefinition(OTHER_SPECIES, 1, FAMILY, ALT_RIG, wolf.starterGenome()));
        assertThrows(IllegalStateException.class, unsupportedRig::freeze);
        var wrongVersion = new Genome(new RulesetVersion(RULESET.id(), 2), wolf.starterGenome().loci());
        var badGenome = baseBuilder().registerFamily("genesis", starter.families().get(FAMILY))
                .registerSpecies("fixture", new SpeciesDefinition(OTHER_SPECIES, 1, FAMILY, RIG, wrongVersion));
        assertThrows(IllegalStateException.class, badGenome::freeze);
    }

    @Test
    void namespaceChecksDuplicatesAndSuccessfulFreezeProtectEveryRegistry() {
        var starter = create();
        var builder = fullBuilder();
        var rig = starter.rigs().get(RIG);
        var family = starter.families().get(FAMILY);
        var species = starter.species().get(WOLF);
        var ruleset = starter.rulesets().get(RULESET.id());
        for (Runnable invalid : new Runnable[]{
                () -> builder.registerRig("fixture", rig), () -> builder.registerFamily("fixture", family),
                () -> builder.registerSpecies("fixture", species), () -> builder.registerRuleset("fixture", ruleset),
                () -> builder.registerRig("genesis", rig), () -> builder.registerFamily("genesis", family),
                () -> builder.registerSpecies("genesis", species), () -> builder.registerRuleset("genesis", ruleset)}) {
            assertThrows(IllegalArgumentException.class, invalid::run);
        }
        var frozen = builder.freeze();
        assertSame(frozen, builder.freeze());
        for (Runnable mutation : new Runnable[]{
                () -> builder.registerRig("genesis", rig), () -> builder.registerFamily("genesis", family),
                () -> builder.registerSpecies("genesis", species), () -> builder.registerRuleset("genesis", ruleset)}) {
            assertThrows(IllegalStateException.class, mutation::run);
        }
        assertThrows(UnsupportedOperationException.class, () -> frozen.rigs().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.families().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.species().clear());
        assertThrows(UnsupportedOperationException.class, () -> frozen.rulesets().clear());
    }

    @Test
    void definitionsRequireVersionsAndDefensivelyCopySupportedRigs() {
        var rigs = new HashSet<>(Set.of(RIG));
        var family = new FamilyDefinition(FAMILY, 1, rigs, RULESET);
        rigs.clear();
        assertEquals(Set.of(RIG), family.supportedRigs());
        assertThrows(UnsupportedOperationException.class, () -> family.supportedRigs().clear());
        assertThrows(IllegalArgumentException.class, () -> new RigDefinition(RIG, 0));
        assertThrows(IllegalArgumentException.class, () -> new FamilyDefinition(FAMILY, 0, Set.of(RIG), RULESET));
        assertThrows(IllegalArgumentException.class, () -> new FamilyDefinition(FAMILY, 1, Set.of(), RULESET));
        assertThrows(IllegalArgumentException.class, () -> new SpeciesDefinition(WOLF, 0, FAMILY, RIG,
                create().species().get(WOLF).starterGenome()));
        assertThrows(IllegalArgumentException.class, () -> GeneticsCatalog.builder(FAMILY, 0));
    }
}
