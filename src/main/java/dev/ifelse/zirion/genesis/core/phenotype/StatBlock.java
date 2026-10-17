package dev.ifelse.zirion.genesis.core.phenotype;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Complete immutable values, or dimensionless multipliers, for the ten semantic stats. */
public record StatBlock(Map<Stat, Double> values) {
    public StatBlock {
        Objects.requireNonNull(values, "values");
        EnumMap<Stat, Double> copy = new EnumMap<>(Stat.class);
        copy.putAll(values);
        for (Stat stat : Stat.values()) {
            Double value = copy.get(stat);
            if (value == null || !Double.isFinite(value) || value < 0) {
                throw new IllegalArgumentException("Missing, negative or non-finite stat: " + stat);
            }
        }
        values = Collections.unmodifiableMap(copy);
    }

    /** Current factors never modify the inherited potential. No second genetic contribution here. */
    public StatBlock actual(StatBlock training, StatBlock condition, StatBlock environment) {
        Objects.requireNonNull(training, "training");
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(environment, "environment");
        EnumMap<Stat, Double> result = new EnumMap<>(Stat.class);
        for (Stat stat : Stat.values()) {
            double value = BigDecimal.valueOf(values.get(stat))
                    .multiply(BigDecimal.valueOf(training.values.get(stat)))
                    .multiply(BigDecimal.valueOf(condition.values.get(stat)))
                    .multiply(BigDecimal.valueOf(environment.values.get(stat))).doubleValue();
            if (!Double.isFinite(value)) throw new IllegalArgumentException("Actual stat overflow: " + stat);
            result.put(stat, value);
        }
        return new StatBlock(result);
    }
}
