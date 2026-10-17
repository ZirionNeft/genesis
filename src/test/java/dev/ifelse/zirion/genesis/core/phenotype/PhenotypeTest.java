package dev.ifelse.zirion.genesis.core.phenotype;

import dev.ifelse.zirion.genesis.core.content.CanidStarterContent;
import dev.ifelse.zirion.genesis.core.content.GeneticsCatalog;
import dev.ifelse.zirion.genesis.core.genetics.AllelePair;
import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.GeneticsRuleset;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.Locus;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static dev.ifelse.zirion.genesis.core.phenotype.PhenotypeRules.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic numbers exercise the contract, not the proposed balance of wolf/fox. */
class PhenotypeTest {
    private static final GeneticsCatalog CATALOG = CanidStarterContent.create();
    private static final GeneticsRuleset GENETICS = CATALOG.rulesets().get(CanidStarterContent.RULESET.id());
    private static final Genome WOLF = CATALOG.species().get(CanidStarterContent.WOLF).starterGenome();
    private static final Genome FOX = CATALOG.species().get(CanidStarterContent.FOX).starterGenome();
    private static final RulesetVersion PROFILE = new RulesetVersion(ContentId.parse("fixture:phenotype"), 1);

    private static Trait trait(Locus locus, String variant) {
        return new Trait(locus, ContentId.parse("genesis:expression/" + locus.name().toLowerCase(Locale.ROOT) + "/" + variant));
    }

    private static Map<Trait, Effect> effects() {
        var effects = new HashMap<Trait, Effect>();
        for (Locus locus : Locus.values()) {
            for (ContentId expression : GENETICS.expressionOptions(locus)) {
                effects.put(new Trait(locus, expression), new Effect(Map.of(), Optional.empty()));
            }
        }
        return effects;
    }

    private static Map<Stat, Range> ranges() {
        var ranges = new EnumMap<Stat, Range>(Stat.class);
        for (Stat stat : Stat.values()) ranges.put(stat, new Range(50, 20, 80));
        return ranges;
    }

    private static StatBlock block(double value) {
        var values = new EnumMap<Stat, Double>(Stat.class);
        for (Stat stat : Stat.values()) values.put(stat, value);
        return new StatBlock(values);
    }

    private static PhenotypeRules rules(Map<Trait, Effect> effects) {
        return new PhenotypeRules(PROFILE, GENETICS, ranges(), effects, List.of());
    }

    private static Genome replace(Genome genome, Locus locus, String a, String b) {
        var loci = new EnumMap<>(genome.loci());
        String prefix = "genesis:allele/" + locus.name().toLowerCase(Locale.ROOT) + "/";
        loci.put(locus, new AllelePair(ContentId.parse(prefix + a), ContentId.parse(prefix + b)));
        return new Genome(genome.ruleset(), loci);
    }

    @Test
    void expressedEffectsAndExplicitInteractionsAreAddedOnceWithTheirCosts() {
        var effects = effects();
        effects.put(trait(Locus.FRAME, "large"), new Effect(Map.of(Stat.STRENGTH, 0.20, Stat.SPEED, -0.10), Optional.empty()));
        effects.put(trait(Locus.MUSCLE, "strong"), new Effect(Map.of(Stat.STRENGTH, 0.10), Optional.empty()));
        var interaction = new Interaction(ContentId.parse("fixture:ram"), Map.of(
                Locus.FRAME, trait(Locus.FRAME, "large").expression(),
                Locus.MUSCLE, trait(Locus.MUSCLE, "strong").expression()),
                Map.of(Stat.STRENGTH, 0.10, Stat.STAMINA, -0.10));
        var rules = new PhenotypeRules(PROFILE, GENETICS, ranges(), effects, List.of(interaction));
        var wolf = rules.resolve(WOLF);
        assertEquals(70.0, wolf.potential().values().get(Stat.STRENGTH));
        assertEquals(45.0, wolf.potential().values().get(Stat.SPEED));
        assertEquals(45.0, wolf.potential().values().get(Stat.STAMINA));
        assertEquals(block(50), rules.resolve(FOX).potential());
        assertEquals(wolf, rules.resolve(WOLF));
        assertEquals(PROFILE, rules.reference());
        assertEquals(GENETICS.reference(), rules.geneticsReference());
    }

    @Test
    void hiddenAllelesDoNotGrantUnexpressedEffectsOrAnExtraAbility() {
        var effects = effects();
        effects.put(trait(Locus.FRAME, "very_large"), new Effect(Map.of(Stat.STRENGTH, 0.50), Optional.empty()));
        effects.put(trait(Locus.ARCANA, "shadow_affinity"), new Effect(Map.of(), Optional.of(ContentId.parse("fixture:shadow_dash"))));
        var rules = rules(effects);
        // WOLF carries normal/large, but only the heterozygous expression 'large' contributes.
        assertEquals(block(50), rules.resolve(WOLF).potential());
        assertEquals(Optional.empty(), rules.resolve(WOLF).activeAbility());
        var carrier = replace(WOLF, Locus.ARCANA, "dormant", "shadow_affinity");
        assertEquals(Optional.of(ContentId.parse("fixture:shadow_dash")), rules.resolve(carrier).activeAbility());
        effects.put(trait(Locus.HEAD, "wolf"), new Effect(Map.of(), Optional.of(ContentId.parse("fixture:extra_ability"))));
        assertThrows(IllegalArgumentException.class, () -> rules(effects));
    }

    @Test
    void extremeModifiersAreSummedExactlyBeforeClamping() {
        var effects = effects();
        effects.put(trait(Locus.FRAME, "large"), new Effect(Map.of(Stat.STRENGTH, Double.MAX_VALUE,
                Stat.HEALTH, -Double.MAX_VALUE), Optional.empty()));
        effects.put(trait(Locus.HEAD, "wolf"), new Effect(Map.of(Stat.STRENGTH, Double.MAX_VALUE), Optional.empty()));
        effects.put(trait(Locus.MUSCLE, "strong"), new Effect(Map.of(Stat.STRENGTH, -Double.MAX_VALUE), Optional.empty()));
        var potential = rules(effects).resolve(WOLF).potential();
        assertEquals(80.0, potential.values().get(Stat.STRENGTH));
        assertEquals(20.0, potential.values().get(Stat.HEALTH));
        effects.put(trait(Locus.HEAD, "wolf"), new Effect(Map.of(Stat.STRENGTH, -Double.MAX_VALUE), Optional.empty()));
        effects.put(trait(Locus.MUSCLE, "strong"), new Effect(Map.of(), Optional.empty()));
        assertEquals(50.0, rules(effects).resolve(WOLF).potential().values().get(Stat.STRENGTH));
    }

    @Test
    void trainingConditionAndEnvironmentChangeOnlyActualEfficiency() {
        var potential = rules(effects()).resolve(WOLF).potential();
        assertEquals(block(30), potential.actual(block(1.2), block(0.5), block(1)));
        assertEquals(block(50), potential);
        assertEquals(block(0), potential.actual(block(Double.MAX_VALUE), block(Double.MAX_VALUE), block(0)));
        // Intermediate double arithmetic would overflow even though the final product fits.
        var safe = block(Double.MAX_VALUE).actual(block(Double.MAX_VALUE), block(1e-308), block(1e-308));
        assertTrue(safe.values().get(Stat.HEALTH) > 3 && safe.values().get(Stat.HEALTH) < 4);
        assertThrows(IllegalArgumentException.class,
                () -> potential.actual(block(Double.MAX_VALUE), block(Double.MAX_VALUE), block(1)));
    }

    @Test
    void allExpressionsNeedExplicitEffectsIncludingNoneAndReferencesAreValidated() {
        var incomplete = effects();
        assertEquals(33, incomplete.size());
        incomplete.remove(trait(Locus.HEAD, "wolf_fox"));
        assertThrows(IllegalArgumentException.class, () -> rules(incomplete));
        var invalid = effects();
        invalid.put(new Trait(Locus.HEAD, trait(Locus.FRAME, "normal").expression()), new Effect(Map.of(), Optional.empty()));
        assertThrows(IllegalArgumentException.class, () -> rules(invalid));
        var badRanges = ranges();
        badRanges.remove(Stat.DEFENSE);
        assertThrows(IllegalArgumentException.class,
                () -> new PhenotypeRules(PROFILE, GENETICS, badRanges, effects(), List.of()));
    }

    @Test
    void unknownInteractionsDuplicatesAndInvalidGenomesAreRejected() {
        var requirements = Map.of(Locus.FRAME, trait(Locus.FRAME, "large").expression(),
                Locus.HEAD, trait(Locus.HEAD, "wolf").expression());
        var interaction = new Interaction(ContentId.parse("fixture:interaction"), requirements, Map.of());
        assertThrows(IllegalArgumentException.class, () -> new PhenotypeRules(PROFILE, GENETICS, ranges(),
                effects(), List.of(interaction, interaction)));
        var unknown = new Interaction(ContentId.parse("fixture:unknown"), Map.of(
                Locus.FRAME, ContentId.parse("missing:expression"), Locus.HEAD, requirements.get(Locus.HEAD)), Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> new PhenotypeRules(PROFILE, GENETICS, ranges(), effects(), List.of(unknown)));
        assertThrows(IllegalArgumentException.class,
                () -> new Interaction(ContentId.parse("fixture:single"), Map.of(Locus.HEAD, requirements.get(Locus.HEAD)), Map.of()));
        var rules = rules(effects());
        var wrongVersion = new Genome(new RulesetVersion(WOLF.ruleset().id(), 2), WOLF.loci());
        assertThrows(IllegalArgumentException.class, () -> rules.resolve(wrongVersion));
        assertThrows(IllegalArgumentException.class, () -> rules.resolve(replace(WOLF, Locus.HEAD, "unknown", "fox")));
    }

    @Test
    void snapshotsAndExpressionOptionsCannotBeChangedAfterRegistration() {
        var effects = effects();
        var ranges = ranges();
        var interactions = new ArrayList<Interaction>();
        var rules = new PhenotypeRules(PROFILE, GENETICS, ranges, effects, interactions);
        effects.clear();
        ranges.clear();
        interactions.add(new Interaction(ContentId.parse("fixture:late"), Map.of(
                Locus.FRAME, trait(Locus.FRAME, "large").expression(), Locus.HEAD, trait(Locus.HEAD, "wolf").expression()),
                Map.of(Stat.HEALTH, -1.0)));
        assertEquals(block(50), rules.resolve(WOLF).potential());
        assertThrows(UnsupportedOperationException.class, () -> GENETICS.expressionOptions(Locus.HEAD).clear());
        assertThrows(UnsupportedOperationException.class, () -> rules.resolve(WOLF).potential().values().clear());
        var input = new EnumMap<>(block(1).values());
        var snapshot = new StatBlock(input);
        input.clear();
        assertEquals(block(1), snapshot);
        var modifiers = new EnumMap<Stat, Double>(Stat.class);
        modifiers.put(Stat.SPEED, 0.1);
        var effect = new Effect(modifiers, Optional.empty());
        modifiers.clear();
        assertEquals(Map.of(Stat.SPEED, 0.1), effect.modifiers());
    }

    @Test
    void invalidValuesMissingStatsAndNonFiniteModifiersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new StatBlock(Map.of()));
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> block(invalid));
            assertThrows(IllegalArgumentException.class, () -> new Range(invalid, 0, 100));
        }
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new Effect(Map.of(Stat.SPEED, invalid), Optional.empty()));
        }
        assertThrows(IllegalArgumentException.class, () -> new Range(50, 60, 80));
        assertThrows(IllegalArgumentException.class, () -> new Range(50, 20, 40));
        assertThrows(IllegalArgumentException.class, () -> new Range(50, -1, 80));
        assertThrows(IllegalArgumentException.class, () -> new Range(50, 20, Double.POSITIVE_INFINITY));
    }
}
