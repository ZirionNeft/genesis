package dev.ifelse.zirion.genesis.core.lineage;

import java.util.Objects;

/** Immutable inputs, not a persisted server configuration. Strict values must be supplied explicitly. */
public record InbreedingPolicy(Mode mode, FrailtySettings frailty, Restrictions restrictions) {
    public enum Mode { OFF, SOFT, STRICT }

    public record FrailtySettings(boolean enabled, double threshold, double riskMultiplier,
                                  double maxProbability, double healthPenalty) {
        public FrailtySettings {
            probability(threshold, "frailty threshold");
            probability(maxProbability, "frailty maximum probability");
            probability(healthPenalty, "frailty health penalty");
            if (!Double.isFinite(riskMultiplier) || riskMultiplier < 0) {
                throw new IllegalArgumentException("Risk multiplier must be finite and nonnegative");
            }
        }
    }

    public record Restrictions(boolean allowRelatedBreeding, double maxCoefficient) {
        public Restrictions { probability(maxCoefficient, "maximum inbreeding coefficient"); }
    }

    public InbreedingPolicy {
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(frailty, "frailty");
        Objects.requireNonNull(restrictions, "restrictions");
    }

    public static InbreedingPolicy softDefaults() {
        return new InbreedingPolicy(Mode.SOFT, new FrailtySettings(true, 0.0625, 0.2, 0.15, 0.08),
                new Restrictions(true, 0.5));
    }

    private static void probability(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException(name + " must be in [0,1]");
    }
}
