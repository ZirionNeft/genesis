package dev.ifelse.zirion.genesis.core.lineage;

import java.math.BigDecimal;
import java.util.Objects;

/** Separate, optional birth condition; this class never reads or rewrites genes or their probabilities. */
public final class InbreedingRules {
    public static final int ALGORITHM_VERSION = 1;

    public sealed interface Assessment permits Allowed, Rejected, Indeterminate {}
    public enum Reason { RELATED_BREEDING_DISABLED, COEFFICIENT_LIMIT, UNCERTAIN_LIMIT, UNCERTAIN_FRAILTY_RISK }

    public record Allowed(BigDecimal frailtyProbability, double healthPenalty, boolean partialPedigree) implements Assessment {
        public Allowed {
            frailtyProbability = probability(frailtyProbability);
            probability(BigDecimal.valueOf(healthPenalty));
        }
    }

    public record Rejected(Reason reason) implements Assessment {
        public Rejected { Objects.requireNonNull(reason, "reason"); }
    }

    public record Indeterminate(Reason reason, BigDecimal lowerRisk, BigDecimal upperRisk) implements Assessment {
        public Indeterminate {
            Objects.requireNonNull(reason, "reason");
            lowerRisk = probability(lowerRisk);
            upperRisk = probability(upperRisk);
            if (lowerRisk.compareTo(upperRisk) > 0) throw new IllegalArgumentException("Risk interval is reversed");
        }
    }

    /** Immutable congenital state to store with the individual, never in the inherited genome/code. */
    public record FrailtyOutcome(BigDecimal assessedProbability, boolean affected, double healthMultiplier) {
        public FrailtyOutcome {
            assessedProbability = probability(assessedProbability);
            probability(BigDecimal.valueOf(healthMultiplier));
            if (!affected && healthMultiplier != 1) throw new IllegalArgumentException("Unaffected health multiplier must equal 1");
        }
    }

    private InbreedingRules() {}

    /** A partial pedigree is neither an automatic zero coefficient nor permission to cross a limit. */
    public static Assessment assess(Pedigree.Estimate estimate, InbreedingPolicy policy) {
        Objects.requireNonNull(estimate, "estimate");
        Objects.requireNonNull(policy, "policy");
        if (policy.mode() == InbreedingPolicy.Mode.OFF) return new Allowed(BigDecimal.ZERO, 0, estimate.partial());

        var restrictions = policy.restrictions();
        BigDecimal limit = restrictions.allowRelatedBreeding() ? BigDecimal.valueOf(restrictions.maxCoefficient()) : BigDecimal.ZERO;
        if (estimate.lowerBound().compareTo(limit) > 0) {
            return new Rejected(restrictions.allowRelatedBreeding() ? Reason.COEFFICIENT_LIMIT : Reason.RELATED_BREEDING_DISABLED);
        }
        BigDecimal low = risk(estimate.lowerBound(), policy.frailty());
        BigDecimal high = risk(estimate.upperBound(), policy.frailty());
        if (estimate.upperBound().compareTo(limit) > 0) return new Indeterminate(Reason.UNCERTAIN_LIMIT, low, high);
        if (low.compareTo(high) != 0) return new Indeterminate(Reason.UNCERTAIN_FRAILTY_RISK, low, high);
        return new Allowed(low, policy.frailty().enabled() ? policy.frailty().healthPenalty() : 0, estimate.partial());
    }

    /** Only an allowed exact risk can be sampled; zero risk consumes no random values. */
    public static FrailtyOutcome roll(Allowed assessment, FrailtyRandom random) {
        Objects.requireNonNull(assessment, "assessment");
        Objects.requireNonNull(random, "random");
        BigDecimal chance = assessment.frailtyProbability();
        if (chance.signum() == 0) return new FrailtyOutcome(chance, false, 1);
        double draw = random.nextUnit();
        if (!Double.isFinite(draw) || draw < 0 || draw >= 1) throw new IllegalArgumentException("RNG value must be in [0,1)");
        boolean affected = BigDecimal.valueOf(draw).compareTo(chance) < 0;
        double multiplier = affected ? BigDecimal.ONE.subtract(BigDecimal.valueOf(assessment.healthPenalty())).doubleValue() : 1;
        return new FrailtyOutcome(chance, affected, multiplier);
    }

    private static BigDecimal risk(BigDecimal coefficient, InbreedingPolicy.FrailtySettings settings) {
        if (!settings.enabled()) return BigDecimal.ZERO;
        return coefficient.subtract(BigDecimal.valueOf(settings.threshold())).max(BigDecimal.ZERO)
                .multiply(BigDecimal.valueOf(settings.riskMultiplier()))
                .min(BigDecimal.valueOf(settings.maxProbability())).stripTrailingZeros();
    }

    private static BigDecimal probability(BigDecimal value) {
        Objects.requireNonNull(value, "probability");
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) throw new IllegalArgumentException("Expected probability [0,1]");
        return value.stripTrailingZeros();
    }
}
