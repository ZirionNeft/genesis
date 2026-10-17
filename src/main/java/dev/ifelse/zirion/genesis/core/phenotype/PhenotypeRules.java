package dev.ifelse.zirion.genesis.core.phenotype;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import dev.ifelse.zirion.genesis.core.genetics.GeneticsRuleset;
import dev.ifelse.zirion.genesis.core.genetics.Genome;
import dev.ifelse.zirion.genesis.core.genetics.Locus;
import dev.ifelse.zirion.genesis.core.genetics.RulesetVersion;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Frozen data-driven archetype profile and expressed effects. Does not choose an archetype or balance. */
public final class PhenotypeRules {
    public record Trait(Locus locus, ContentId expression) {
        public Trait {
            Objects.requireNonNull(locus, "locus");
            Objects.requireNonNull(expression, "expression");
        }
    }

    public record Range(double base, double lower, double upper) {
        public Range {
            if (!Double.isFinite(base) || !Double.isFinite(lower) || !Double.isFinite(upper)
                    || lower < 0 || lower > base || base > upper) {
                throw new IllegalArgumentException("Expected finite 0 <= lower <= base <= upper");
            }
        }
    }

    /** Fractional additive modifiers, including costs; an empty map explicitly means no stat effect. */
    public record Effect(Map<Stat, Double> modifiers, Optional<ContentId> activeAbility) {
        public Effect {
            modifiers = copyModifiers(modifiers);
            Objects.requireNonNull(activeAbility, "activeAbility");
        }
    }

    public record Interaction(ContentId id, Map<Locus, ContentId> requires, Map<Stat, Double> modifiers) {
        public Interaction {
            Objects.requireNonNull(id, "id");
            requires = Map.copyOf(requires);
            if (requires.size() < 2) throw new IllegalArgumentException("Interaction needs at least two loci");
            modifiers = copyModifiers(modifiers);
        }
    }

    public record Phenotype(StatBlock potential, Optional<ContentId> activeAbility) {
        public Phenotype {
            Objects.requireNonNull(potential, "potential");
            Objects.requireNonNull(activeAbility, "activeAbility");
        }
    }

    private final RulesetVersion reference;
    private final GeneticsRuleset genetics;
    private final Map<Stat, Range> ranges;
    private final Map<Trait, Effect> effects;
    private final List<Interaction> interactions;

    /** Incomplete effects fail at construction, before any creature can be calculated. */
    public PhenotypeRules(RulesetVersion reference, GeneticsRuleset genetics, Map<Stat, Range> ranges,
                          Map<Trait, Effect> effects, List<Interaction> interactions) {
        this.reference = Objects.requireNonNull(reference, "reference");
        this.genetics = Objects.requireNonNull(genetics, "genetics");
        this.ranges = Map.copyOf(ranges);
        this.effects = Map.copyOf(effects);
        this.interactions = interactions.stream().sorted(java.util.Comparator.comparing(Interaction::id)).toList();
        for (Stat stat : Stat.values()) {
            if (!this.ranges.containsKey(stat)) throw new IllegalArgumentException("Missing range: " + stat);
        }
        this.effects.forEach((trait, effect) -> {
            requireTrait(trait);
            if (effect.activeAbility().isPresent() && trait.locus() != Locus.ARCANA) {
                throw new IllegalArgumentException("Active inherited ability must be expressed by ARCANA");
            }
        });
        for (Locus locus : Locus.values()) {
            for (ContentId expression : genetics.expressionOptions(locus)) {
                if (!this.effects.containsKey(new Trait(locus, expression))) {
                    throw new IllegalArgumentException("Missing effect at " + locus + ": " + expression);
                }
            }
        }
        var ids = new HashSet<ContentId>();
        for (Interaction interaction : this.interactions) {
            if (!ids.add(interaction.id())) throw new IllegalArgumentException("Duplicate interaction: " + interaction.id());
            interaction.requires().forEach((locus, expression) -> requireTrait(new Trait(locus, expression)));
        }
    }

    public RulesetVersion reference() { return reference; }

    public RulesetVersion geneticsReference() { return genetics.reference(); }

    /** S = clamp(B * (1 + sum(expressed effects) + sum(explicit interactions)), L, U). */
    public Phenotype resolve(Genome genome) {
        Map<Locus, ContentId> expressed = genetics.resolveExpressions(genome);
        EnumMap<Stat, BigDecimal> totals = new EnumMap<>(Stat.class);
        for (Stat stat : Stat.values()) totals.put(stat, BigDecimal.ONE);
        for (Locus locus : Locus.values()) add(totals, effects.get(new Trait(locus, expressed.get(locus))).modifiers());
        for (Interaction interaction : interactions) {
            if (interaction.requires().entrySet().stream().allMatch(entry -> entry.getValue().equals(expressed.get(entry.getKey())))) {
                add(totals, interaction.modifiers());
            }
        }
        EnumMap<Stat, Double> potential = new EnumMap<>(Stat.class);
        for (Stat stat : Stat.values()) {
            Range range = ranges.get(stat);
            BigDecimal value = BigDecimal.valueOf(range.base()).multiply(totals.get(stat));
            potential.put(stat, value.max(BigDecimal.valueOf(range.lower()))
                    .min(BigDecimal.valueOf(range.upper())).doubleValue());
        }
        return new Phenotype(new StatBlock(potential), effects.get(new Trait(Locus.ARCANA,
                expressed.get(Locus.ARCANA))).activeAbility());
    }

    private void requireTrait(Trait trait) {
        if (!genetics.expressionOptions(trait.locus()).contains(trait.expression())) {
            throw new IllegalArgumentException("Unknown or misplaced expression: " + trait);
        }
    }

    private static Map<Stat, Double> copyModifiers(Map<Stat, Double> modifiers) {
        EnumMap<Stat, Double> copy = new EnumMap<>(Stat.class);
        copy.putAll(modifiers);
        copy.forEach((stat, value) -> {
            if (value == null || !Double.isFinite(value)) throw new IllegalArgumentException("Non-finite modifier: " + stat);
        });
        return Collections.unmodifiableMap(copy);
    }

    private static void add(EnumMap<Stat, BigDecimal> totals, Map<Stat, Double> modifiers) {
        modifiers.forEach((stat, value) -> totals.put(stat, totals.get(stat).add(BigDecimal.valueOf(value))));
    }
}
