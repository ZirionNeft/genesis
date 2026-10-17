package dev.ifelse.zirion.genesis.core.mutation;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

import static dev.ifelse.zirion.genesis.core.mutation.MutationDefinition.Impact;

/** Finite probabilities, not gameplay configuration/storage. Defaults are the provisional GDD weights. */
public record MutationPolicy(Map<MutationCategory, Double> categoryChances,
                             Map<Impact, Double> impactChances, UnavailablePolicy unavailablePolicy) {
    public enum UnavailablePolicy { SKIP }

    public MutationPolicy {
        Objects.requireNonNull(unavailablePolicy, "unavailablePolicy");
        EnumMap<MutationCategory, Double> categories = new EnumMap<>(MutationCategory.class);
        categories.putAll(categoryChances);
        BigDecimal total = BigDecimal.ZERO;
        for (MutationCategory category : MutationCategory.values()) {
            total = total.add(probability(categories.get(category), category.toString()));
        }
        if (total.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("Mutation probability exceeds 1");
        EnumMap<Impact, Double> impacts = new EnumMap<>(Impact.class);
        impacts.putAll(impactChances);
        if (impacts.containsKey(Impact.COSMETIC)) throw new IllegalArgumentException("Cosmetic has no impact draw");
        BigDecimal impactTotal = BigDecimal.ZERO;
        for (Impact impact : Impact.values()) {
            if (impact != Impact.COSMETIC) impactTotal = impactTotal.add(probability(impacts.get(impact), impact.toString()));
        }
        if (impactTotal.compareTo(BigDecimal.ONE) != 0) throw new IllegalArgumentException("Impact probabilities must sum to 1");
        categoryChances = Collections.unmodifiableMap(categories);
        impactChances = Collections.unmodifiableMap(impacts);
    }

    public static MutationPolicy gddDefaults() {
        return new MutationPolicy(Map.of(MutationCategory.COSMETIC, 0.04,
                MutationCategory.PHYSIOLOGICAL, 0.025, MutationCategory.BEHAVIORAL_SENSORY, 0.01,
                MutationCategory.ARCANE, 0.005), Map.of(Impact.BENEFICIAL, 0.3,
                Impact.TRADEOFF, 0.4, Impact.HARMFUL, 0.3), UnavailablePolicy.SKIP);
    }

    public BigDecimal noMutationChance() {
        BigDecimal remaining = BigDecimal.ONE;
        for (MutationCategory category : MutationCategory.values()) {
            remaining = remaining.subtract(BigDecimal.valueOf(categoryChances.get(category)));
        }
        return remaining;
    }

    private static BigDecimal probability(Double value, String name) {
        if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException("Expected finite probability [0,1]: " + name);
        }
        return BigDecimal.valueOf(value);
    }
}
