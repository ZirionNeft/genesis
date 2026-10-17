package dev.ifelse.zirion.genesis.core.breeding;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BreedingChanceTest {
    @Test
    void reproducesGddExampleWithMultipliersAboveOne() {
        assertEquals(0.8855, BreedingChance.calculate(0.70, 1, 1, 1.1, 1.15), 1e-12);
    }

    @Test
    void accountsForBothParentsReadiness() {
        assertEquals(0.18, BreedingChance.calculate(0.90, 0.5, 0.4, 1, 1), 1e-12);
    }

    @Test
    void capsEvenPerfectConditionsBelowGuaranteedSuccess() {
        assertEquals(0.95, BreedingChance.calculate(0.90, 1, 1, 1.1, 1.15));
        assertEquals(0.95, BreedingChance.calculate(1, 1, 1, Double.MAX_VALUE, Double.MAX_VALUE));
    }

    @Test
    void anyZeroFactorMakesAttemptChanceZero() {
        for (int index = 0; index < 5; index++) {
            double[] factors = {1, 1, 1, 1, Double.MAX_VALUE};
            factors[index] = 0;
            assertEquals(0, calculate(factors), "zero factor " + index);
        }
        assertEquals(0, BreedingChance.calculate(1, 1, 1, Double.MAX_VALUE, 0));
    }

    @Test
    void doesNotLoseSmallFactorsBeforeLargeMultipliersCompensateThem() {
        assertEquals(0.95, BreedingChance.calculate(1e-300, 1e-300, 1, 1e300, 1e300));
    }

    @Test
    void rejectsNonfiniteAndNegativeInputsEvenWhenAnotherFactorIsZero() {
        for (int index = 0; index < 5; index++) {
            for (double invalid : new double[]{-0.01, Double.NaN,
                    Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
                double[] factors = {0, 1, 1, 1, 1};
                factors[index] = invalid;
                assertThrows(IllegalArgumentException.class, () -> calculate(factors),
                        "invalid factor " + index + " = " + invalid);
            }
        }
    }

    @Test
    void rejectsProbabilitiesAboveOne() {
        for (int index = 0; index < 3; index++) {
            double[] factors = {1, 1, 1, 1, 1};
            factors[index] = 1.01;
            assertThrows(IllegalArgumentException.class, () -> calculate(factors));
        }
    }

    private static double calculate(double[] factors) {
        return BreedingChance.calculate(factors[0], factors[1], factors[2], factors[3], factors[4]);
    }
}
