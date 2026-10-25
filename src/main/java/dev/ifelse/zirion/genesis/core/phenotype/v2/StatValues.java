package dev.ifelse.zirion.genesis.core.phenotype.v2;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Complete nonnegative decimal values. Founders, minimums and rounded results use the same units. */
public record StatValues(Map<Stat, BigDecimal> values) {
    public StatValues {
        var copy = new EnumMap<Stat, BigDecimal>(Stat.class);
        copy.putAll(values);
        for (Stat stat : Stat.values()) {
            BigDecimal value = Objects.requireNonNull(copy.get(stat), "Missing stat: " + stat);
            if (value.signum() < 0) throw new IllegalArgumentException("Negative stat: " + stat);
        }
        values = Collections.unmodifiableMap(copy);
    }

    public BigDecimal get(Stat stat) { return values.get(Objects.requireNonNull(stat)); }
}
