package dev.ifelse.zirion.genesis.core.content;

import dev.ifelse.zirion.genesis.core.genetics.*;
import dev.ifelse.zirion.genesis.core.phenotype.PhenotypeRules;
import dev.ifelse.zirion.genesis.core.phenotype.Stat;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static dev.ifelse.zirion.genesis.core.content.CanidBreedingContent.Archetype;
import static dev.ifelse.zirion.genesis.core.content.CanidBreedingContent.expression;
import static dev.ifelse.zirion.genesis.core.phenotype.PhenotypeRules.*;
import static dev.ifelse.zirion.genesis.core.phenotype.Stat.*;

/** Provisional relative numbers for D05/D06, without Hytale units, ability execution or archetype selection. */
final class CanidPhenotypeContent {
    private CanidPhenotypeContent() {}

    static Map<Archetype, PhenotypeRules> create(GeneticsRuleset genetics) {
        Map<Trait, Effect> effects = effects();
        List<Interaction> interactions = List.of(
                interaction("large_hypertrophy", Locus.FRAME, "large", Locus.MUSCLE, "hypertrophy",
                        Map.of(STRENGTH, 0.05, STAMINA, -0.10, METABOLISM, 0.08)),
                interaction("very_large_hypertrophy", Locus.FRAME, "very_large", Locus.MUSCLE, "hypertrophy",
                        Map.of(STRENGTH, 0.05, STAMINA, -0.10, METABOLISM, 0.08)),
                interaction("large_fragile_bones", Locus.FRAME, "large", Locus.LIMBS, "fragile_bones", Map.of(LOAD, -0.20)),
                interaction("very_large_fragile_bones", Locus.FRAME, "very_large", Locus.LIMBS, "fragile_bones", Map.of(LOAD, -0.30)),
                interaction("regeneration_fast_metabolism", Locus.ARCANA, "regeneration", Locus.METABOLISM, "fast",
                        Map.of(METABOLISM, 0.15)));
        var result = new EnumMap<Archetype, PhenotypeRules>(Archetype.class);
        for (Archetype archetype : Archetype.values()) {
            result.put(archetype, new PhenotypeRules(new RulesetVersion(ContentId.parse("genesis:canid_"
                    + archetype.name().toLowerCase(Locale.ROOT) + "_phenotype_draft"), 1), genetics,
                    ranges(archetype), effects, interactions));
        }
        return Map.copyOf(result);
    }

    private static Map<Trait, Effect> effects() {
        var effects = new HashMap<Trait, Effect>();
        effect(effects, Locus.FRAME, "normal", Map.of());
        effect(effects, Locus.FRAME, "large", Map.of(HEALTH, 0.12, STRENGTH, 0.08, SPEED, -0.05, AGILITY, -0.06, LOAD, 0.20, METABOLISM, 0.08));
        effect(effects, Locus.FRAME, "very_large", Map.of(HEALTH, 0.25, STRENGTH, 0.15, SPEED, -0.12, AGILITY, -0.15, LOAD, 0.35, METABOLISM, 0.18));
        for (String value : List.of("wolf", "wolf_fox", "fox")) effect(effects, Locus.HEAD, value, Map.of());
        effect(effects, Locus.LIMBS, "normal", Map.of());
        effect(effects, Locus.LIMBS, "athletic", Map.of(SPEED, 0.05, AGILITY, 0.05, LOAD, -0.05));
        effect(effects, Locus.LIMBS, "runner", Map.of(SPEED, 0.10, AGILITY, 0.10, HEALTH, -0.05, LOAD, -0.15));
        effect(effects, Locus.LIMBS, "fragile_bones", Map.of(HEALTH, -0.20, DEFENSE, -0.12, LOAD, -0.10));
        for (String value : List.of("wolf_tail", "bushy_tail")) effect(effects, Locus.APPENDAGE, value, Map.of());
        for (String value : List.of("grey", "grey_orange", "orange", "shadow")) effect(effects, Locus.PIGMENT, value, Map.of());
        effect(effects, Locus.COVER, "dense_fur", Map.of(DEFENSE, 0.08, SPEED, -0.02, METABOLISM, 0.04));
        effect(effects, Locus.COVER, "medium_fur", Map.of(DEFENSE, 0.04, METABOLISM, 0.02));
        effect(effects, Locus.COVER, "short_fur", Map.of());
        effect(effects, Locus.MUSCLE, "strong", Map.of(STRENGTH, 0.10, LOAD, 0.15, SPEED, -0.04, METABOLISM, 0.10));
        effect(effects, Locus.MUSCLE, "balanced", Map.of(STRENGTH, 0.05, LOAD, 0.07, METABOLISM, 0.05));
        effect(effects, Locus.MUSCLE, "normal", Map.of());
        effect(effects, Locus.MUSCLE, "hypertrophy_mild", Map.of(STRENGTH, 0.15, SPEED, -0.07, STAMINA, -0.04, METABOLISM, 0.12));
        effect(effects, Locus.MUSCLE, "hypertrophy", Map.of(STRENGTH, 0.20, SPEED, -0.10, STAMINA, -0.08, METABOLISM, 0.20));
        effect(effects, Locus.STAMINA, "normal", Map.of());
        effect(effects, Locus.STAMINA, "high", Map.of(STAMINA, 0.15, STRENGTH, -0.03, METABOLISM, 0.06));
        effect(effects, Locus.STAMINA, "developed_lungs", Map.of(STAMINA, 0.25, STRENGTH, -0.06, METABOLISM, 0.08));
        effect(effects, Locus.METABOLISM, "normal", Map.of());
        effect(effects, Locus.METABOLISM, "intermediate", Map.of(STAMINA, 0.04, METABOLISM, 0.10));
        effect(effects, Locus.METABOLISM, "fast", Map.of(STAMINA, 0.08, METABOLISM, 0.20));
        effect(effects, Locus.SENSE, "keen_smell", Map.of(PERCEPTION, 0.10, METABOLISM, 0.02));
        effect(effects, Locus.SENSE, "mixed_senses", Map.of(PERCEPTION, 0.06));
        effect(effects, Locus.SENSE, "night_vision", Map.of()); // Contextual night sensing is not a permanent generic bonus.
        effect(effects, Locus.TEMPER, "loyal", Map.of(TRAINABILITY, 0.12, STAMINA, -0.02));
        effect(effects, Locus.TEMPER, "watchful", Map.of(TRAINABILITY, 0.05, PERCEPTION, 0.04));
        effect(effects, Locus.TEMPER, "cautious", Map.of(PERCEPTION, 0.08, TRAINABILITY, -0.06));
        effect(effects, Locus.TEMPER, "uneasy", Map.of(TRAINABILITY, -0.10));
        effect(effects, Locus.TEMPER, "nervous", Map.of(TRAINABILITY, -0.25));
        effect(effects, Locus.ARCANA, "dormant", Map.of());
        ability(effects, "shadow_affinity", "shadow_dash", Map.of(STAMINA, -0.08, METABOLISM, 0.12));
        ability(effects, "regeneration", "regeneration", Map.of(STAMINA, -0.05, METABOLISM, 0.20));
        return effects;
    }

    private static void effect(Map<Trait, Effect> effects, Locus locus, String value, Map<Stat, Double> modifiers) {
        if (effects.putIfAbsent(new Trait(locus, expression(locus, value)), new Effect(modifiers, Optional.empty())) != null) {
            throw new IllegalArgumentException("Duplicate canid effect: " + locus + "/" + value);
        }
    }

    private static void ability(Map<Trait, Effect> effects, String value, String ability, Map<Stat, Double> modifiers) {
        effects.put(new Trait(Locus.ARCANA, expression(Locus.ARCANA, value)),
                new Effect(modifiers, Optional.of(ContentId.parse("genesis:ability/" + ability))));
    }

    private static Interaction interaction(String id, Locus a, String av, Locus b, String bv, Map<Stat, Double> modifiers) {
        return new Interaction(ContentId.parse("genesis:interaction/" + id), Map.of(a, expression(a, av), b, expression(b, bv)), modifiers);
    }

    private static Map<Stat, Range> ranges(Archetype archetype) {
        // GDD §11.3 supplies HP/strength/speed/load/perception for originals. Other bases and hybrid means are provisional.
        double[] bases = switch (archetype) {
            case WOLF -> new double[]{65, 70, 50, 75, 65, 60, 15, 70, 70, 50};
            case FOX -> new double[]{35, 40, 35, 90, 55, 85, 5, 95, 50, 45};
            case HYBRID -> new double[]{50, 55, 42.5, 82.5, 60, 72.5, 10, 82.5, 60, 47.5};
        };
        // Relative limits from GDD §11.2; agility/trainability/metabolism limits remain a draft design choice.
        double[] lower = {0.70, 0.70, 0.70, 0.75, 0.70, 0.70, 0.60, 0.70, 0.60, 0.70};
        double[] upper = {1.45, 1.35, 1.40, 1.30, 1.50, 1.40, 1.55, 1.45, 1.40, 1.80};
        var result = new EnumMap<Stat, Range>(Stat.class);
        for (Stat stat : Stat.values()) result.put(stat, new Range(bases[stat.ordinal()],
                bases[stat.ordinal()] * lower[stat.ordinal()], bases[stat.ordinal()] * upper[stat.ordinal()]));
        return result;
    }
}
