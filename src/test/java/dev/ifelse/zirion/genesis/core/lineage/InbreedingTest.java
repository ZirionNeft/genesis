package dev.ifelse.zirion.genesis.core.lineage;

import dev.ifelse.zirion.genesis.core.genetics.ContentId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.ifelse.zirion.genesis.core.lineage.InbreedingPolicy.*;
import static dev.ifelse.zirion.genesis.core.lineage.InbreedingRules.*;
import static org.junit.jupiter.api.Assertions.*;

class InbreedingTest {
    private static final InbreedingPolicy SOFT = InbreedingPolicy.softDefaults();

    private static Pedigree.Estimate exact(String value) {
        return new Pedigree.Estimate(new BigDecimal(value), new BigDecimal(value), Set.of(), 2, 4);
    }

    private static Pedigree.Estimate partial(String lower) {
        return new Pedigree.Estimate(new BigDecimal(lower), BigDecimal.ONE,
                Set.of(Pedigree.Uncertainty.UNKNOWN_PARENT), 2, 4);
    }

    private static InbreedingPolicy unrestricted(FrailtySettings settings) {
        return new InbreedingPolicy(Mode.SOFT, settings, new Restrictions(true, 1));
    }

    private static Allowed allowed(Pedigree.Estimate estimate, InbreedingPolicy policy) {
        return assertInstanceOf(Allowed.class, assess(estimate, policy));
    }

    @Test
    void softRiskMatchesTheGddExampleAndDoesNotApplyBelowTheThreshold() {
        assertEquals(BigDecimal.ZERO, allowed(exact("0"), SOFT).frailtyProbability());
        assertEquals(BigDecimal.ZERO, allowed(exact("0.0625"), SOFT).frailtyProbability());
        assertEquals(new BigDecimal("0.0125"), allowed(exact("0.125"), SOFT).frailtyProbability());
        assertEquals(new BigDecimal("0.0375"), allowed(exact("0.25"), SOFT).frailtyProbability());
        assertEquals(new BigDecimal("0.15"), allowed(exact("1"), unrestricted(SOFT.frailty())).frailtyProbability());
    }

    @Test
    void frailtyDrawHasAStrictBoundaryAndTheStoredOutcomeSurvivesSettingsChanges() {
        var assessment = allowed(exact("0.25"), SOFT);
        var affected = roll(assessment, () -> Math.nextDown(0.0375));
        assertEquals(new FrailtyOutcome(new BigDecimal("0.0375"), true, 0.92), affected);
        assertEquals(new FrailtyOutcome(new BigDecimal("0.0375"), false, 1), roll(assessment, () -> 0.0375));
        var changedSettings = unrestricted(new FrailtySettings(true, 0, 1, 1, 0.5));
        assertEquals(0.5, roll(allowed(exact("0.25"), changedSettings), () -> 0).healthMultiplier());
        assertEquals(0.92, affected.healthMultiplier());
        assertEquals(new BigDecimal("0.0375"), affected.assessedProbability());
    }

    @Test
    void offOverridesEnabledFrailtyAndAllRestrictionsEvenForPartialPedigrees() {
        var off = new InbreedingPolicy(Mode.OFF, new FrailtySettings(true, 0, 100, 1, 1), new Restrictions(false, 0));
        for (var estimate : new Pedigree.Estimate[]{exact("0.25"), exact("1"), partial("0")}) {
            var assessment = allowed(estimate, off);
            assertEquals(BigDecimal.ZERO, assessment.frailtyProbability());
            assertEquals(new FrailtyOutcome(BigDecimal.ZERO, false, 1), roll(assessment, () -> fail("OFF consumed RNG")));
        }
    }

    @Test
    void disablingFrailtyPreservesRestrictionsButAllowsUnknownRiskWhenUnrestricted() {
        var disabled = new FrailtySettings(false, 0, 100, 1, 0.9);
        var strict = new InbreedingPolicy(Mode.STRICT, disabled, new Restrictions(true, 0.125));
        assertEquals(new Rejected(Reason.COEFFICIENT_LIMIT), assess(exact("0.25"), strict));
        var assessment = allowed(partial("0"), unrestricted(disabled));
        assertTrue(assessment.partialPedigree());
        assertEquals(BigDecimal.ZERO, assessment.frailtyProbability());
        assertEquals(1, roll(assessment, () -> fail("Disabled frailty consumed RNG")).healthMultiplier());
    }

    @Test
    void strictUsesExplicitRiskSettingsAndLimitsRatherThanAnInventedPreset() {
        var strict = new InbreedingPolicy(Mode.STRICT, new FrailtySettings(true, 0.0625, 0.8, 0.4, 0.15),
                new Restrictions(true, 0.25));
        assertEquals(new BigDecimal("0.15"), allowed(exact("0.25"), strict).frailtyProbability());
        assertEquals(new Rejected(Reason.COEFFICIENT_LIMIT), assess(exact("0.2501"), strict));
        var forbidden = new InbreedingPolicy(Mode.SOFT, SOFT.frailty(), new Restrictions(false, 1));
        assertEquals(new Rejected(Reason.RELATED_BREEDING_DISABLED), assess(exact("0.0625"), forbidden));
        assertInstanceOf(Allowed.class, assess(exact("0"), forbidden));
    }

    @Test
    void partialPedigreesDoNotPretendToHaveAnExactCoefficientOrFrailtyRisk() {
        var limit = assertInstanceOf(Indeterminate.class, assess(partial("0"), SOFT));
        assertEquals(Reason.UNCERTAIN_LIMIT, limit.reason());
        assertEquals(BigDecimal.ZERO, limit.lowerRisk());
        assertEquals(new BigDecimal("0.15"), limit.upperRisk());
        var risk = assertInstanceOf(Indeterminate.class, assess(partial("0.25"), unrestricted(SOFT.frailty())));
        assertEquals(Reason.UNCERTAIN_FRAILTY_RISK, risk.reason());
        assertEquals(new BigDecimal("0.0375"), risk.lowerRisk());
        assertEquals(new BigDecimal("0.15"), risk.upperRisk());
        // Known evidence already beyond the limit is sufficient for a rejection, despite missing data.
        assertEquals(new Rejected(Reason.COEFFICIENT_LIMIT), assess(partial("0.75"), SOFT));
        var capped = unrestricted(new FrailtySettings(true, 0, 1, 0.2, 0.08));
        var saturated = allowed(partial("0.9"), capped);
        assertEquals(new BigDecimal("0.2"), saturated.frailtyProbability());
        assertTrue(saturated.partialPedigree());
    }

    @Test
    void riskCapsHandleExtremeMultipliersAndZeroWithoutIntermediateOverflow() {
        var extreme = unrestricted(new FrailtySettings(true, 0.0625, Double.MAX_VALUE, 0.15, 0.08));
        assertEquals(new BigDecimal("0.15"), allowed(exact("1"), extreme).frailtyProbability());
        assertEquals(BigDecimal.ZERO, allowed(exact("0.0625"), extreme).frailtyProbability());
        var zero = unrestricted(new FrailtySettings(true, 0, 0, 1, 0.08));
        assertEquals(BigDecimal.ZERO, allowed(partial("0"), zero).frailtyProbability());
    }

    @Test
    void invalidSettingsIntervalsAndInjectedDrawsAreRejected() {
        for (double invalid : new double[]{-1, 1.01, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new FrailtySettings(true, invalid, 0.2, 0.15, 0.08));
            assertThrows(IllegalArgumentException.class, () -> new FrailtySettings(true, 0.0625, 0.2, invalid, 0.08));
            assertThrows(IllegalArgumentException.class, () -> new FrailtySettings(true, 0.0625, 0.2, 0.15, invalid));
            assertThrows(IllegalArgumentException.class, () -> new Restrictions(true, invalid));
        }
        for (double invalid : new double[]{-1, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new FrailtySettings(true, 0.0625, invalid, 0.15, 0.08));
        }
        assertThrows(IllegalArgumentException.class, () -> new Pedigree.Estimate(new BigDecimal("0.5"),
                new BigDecimal("0.25"), Set.of(Pedigree.Uncertainty.DEPTH_LIMIT), 2, 4));
        assertThrows(IllegalArgumentException.class, () -> new Pedigree.Estimate(BigDecimal.ZERO,
                BigDecimal.ONE, Set.of(), 2, 4));
        for (double invalid : new double[]{-1, 1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> roll(allowed(exact("0.25"), SOFT), () -> invalid));
        }
    }

    @Test
    void zeroRiskConsumesNoDrawAndPositiveRiskConsumesExactlyOne() {
        var randomCalls = new AtomicInteger();
        var random = (FrailtyRandom) () -> { randomCalls.incrementAndGet(); return 0; };
        assertFalse(roll(allowed(exact("0"), SOFT), random).affected());
        assertEquals(0, randomCalls.get());
        assertTrue(roll(allowed(exact("0.25"), SOFT), random).affected());
        assertEquals(1, randomCalls.get());
        var certain = unrestricted(new FrailtySettings(true, 0, 1, 1, 0.08));
        assertTrue(roll(allowed(exact("1"), certain), () -> Math.nextDown(1.0)).affected());
    }

    @Test
    void dedicatedSeededFrailtyStreamHasTheKnownJavaSeed42Vector() {
        var random = new SeededFrailtyRandom(42);
        double[] expected = {0.7275636800328681, 0.6832234717598454, 0.30871945533265976,
                0.27707849007413665, 0.6655489517945736};
        for (double draw : expected) assertEquals(draw, random.nextUnit());
        assertEquals(ContentId.parse("genesis:frailty_java_random_lcg48_v1"), SeededFrailtyRandom.ALGORITHM);
    }

    @Test
    void oneHundredThousandBirthConditionsMatchTheGddRiskWithoutChangingItsValue() {
        var assessment = allowed(exact("0.25"), SOFT);
        var random = new SeededFrailtyRandom(0x465241494C54594CL);
        int affected = 0;
        int trials = 100_000;
        for (int birth = 0; birth < trials; birth++) {
            var outcome = roll(assessment, random);
            assertEquals(new BigDecimal("0.0375"), outcome.assessedProbability());
            assertEquals(outcome.affected() ? 0.92 : 1, outcome.healthMultiplier());
            if (outcome.affected()) affected++;
        }
        double p = 0.0375;
        assertEquals(trials * p, affected, 6 * Math.sqrt(trials * p * (1 - p)));
    }
}
