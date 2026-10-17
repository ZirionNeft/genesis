package dev.ifelse.zirion.genesis.core.breeding;

import java.math.BigDecimal;

/** Pure attempt-success calculation from GDD 0.5 §6.4; no genetic sampling or game state. */
public final class BreedingChance {
    private static final BigDecimal MAXIMUM = new BigDecimal("0.95");

    private BreedingChance() {
    }

    /**
     * Calculates clamp(compatibility * readinessA * readinessB * environment * harmony, 0, 0.95).
     * The caller must reject an ineligible pair before starting an attempt or spending resources.
     * Environment and harmony are nonnegative multipliers and may exceed one.
     */
    public static double calculate(double compatibility, double readinessA, double readinessB,
                                   double environment, double harmony) {
        requireProbability("compatibility", compatibility);
        requireProbability("readinessA", readinessA);
        requireProbability("readinessB", readinessB);
        requireMultiplier("environment", environment);
        requireMultiplier("harmony", harmony);

        // Avoid overflow/underflow of intermediate products before applying the final cap.
        return BigDecimal.valueOf(compatibility)
                .multiply(BigDecimal.valueOf(readinessA))
                .multiply(BigDecimal.valueOf(readinessB))
                .multiply(BigDecimal.valueOf(environment))
                .multiply(BigDecimal.valueOf(harmony))
                .min(MAXIMUM)
                .doubleValue();
    }

    private static void requireProbability(String name, double value) {
        requireMultiplier(name, value);
        if (value > 1) {
            throw new IllegalArgumentException(name + " must be in [0, 1]");
        }
    }

    private static void requireMultiplier(String name, double value) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException(name + " must be finite and nonnegative");
        }
    }
}
