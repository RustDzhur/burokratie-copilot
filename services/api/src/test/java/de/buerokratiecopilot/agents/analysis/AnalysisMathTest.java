package de.buerokratiecopilot.agents.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Verifies the pure arithmetic the analysis agents are built on: mean, sample standard
 * deviation, z-scores and least-squares regression. These numbers feed the anomaly and
 * forecast reports, so they are checked against hand-computed values, not against themselves.
 */
class AnalysisMathTest {

    @Test
    void meanOfKnownValues() {
        assertEquals(5.0, AnalysisMath.mean(new double[] {2, 4, 4, 4, 5, 5, 7, 9}), 1e-9);
        assertEquals(0.0, AnalysisMath.mean(new double[0]), 1e-9);
    }

    @Test
    void sampleStdDevOfKnownValues() {
        // Sample standard deviation (n-1): [2,4,4,4,5,5,7,9] → mean 5, Σ(Δ²)=32 → sqrt(32/7).
        double sigma = AnalysisMath.sampleStdDev(new double[] {2, 4, 4, 4, 5, 5, 7, 9});
        assertEquals(Math.sqrt(32.0 / 7.0), sigma, 1e-9);
        // [1,2,3,4,5] → mean 3, Σ(Δ²)=10 → sqrt(10/4) = sqrt(2.5).
        assertEquals(Math.sqrt(2.5),
                AnalysisMath.sampleStdDev(new double[] {1, 2, 3, 4, 5}), 1e-9);
    }

    @Test
    void stdDevNeedsAtLeastTwoValues() {
        assertEquals(0.0, AnalysisMath.sampleStdDev(new double[] {7}), 1e-9);
        assertEquals(0.0, AnalysisMath.sampleStdDev(new double[0]), 1e-9);
    }

    @Test
    void zScoreIsDistanceInSigmas() {
        assertEquals(2.0, AnalysisMath.zScore(14.0, 10.0, 2.0), 1e-9);
        assertEquals(-1.5, AnalysisMath.zScore(7.0, 10.0, 2.0), 1e-9);
        // A zero sigma cannot produce a meaningful z: neutral 0 instead of infinity.
        assertEquals(0.0, AnalysisMath.zScore(14.0, 10.0, 0.0), 1e-9);
    }

    @Test
    void regressionRecoversAnExactLine() {
        double[] x = {0, 1, 2, 3, 4};
        double[] y = {5, 7, 9, 11, 13}; // y = 2x + 5
        AnalysisMath.Regression fit = AnalysisMath.regression(x, y);
        assertEquals(2.0, fit.slope(), 1e-9);
        assertEquals(5.0, fit.intercept(), 1e-9);
        assertEquals(1.0, fit.r2(), 1e-9);
        assertEquals(0.0, fit.residualSigma(), 1e-9);
        assertEquals(15.0, AnalysisMath.predict(fit, 5), 1e-9);
    }

    @Test
    void regressionQuantifiesNoise() {
        double[] x = {0, 1, 2, 3, 4};
        double[] y = {5.1, 6.8, 9.2, 10.9, 13.2}; // 2x + 5 with small noise
        AnalysisMath.Regression fit = AnalysisMath.regression(x, y);
        assertTrue(fit.slope() > 1.5 && fit.slope() < 2.5, "slope " + fit.slope());
        assertTrue(fit.residualSigma() > 0, "noise must widen the band");
        assertTrue(fit.r2() > 0.99, "r2 " + fit.r2());
    }

    @Test
    void regressionHandlesDegenerateInputs() {
        AnalysisMath.Regression empty = AnalysisMath.regression(new double[0], new double[0]);
        assertEquals(0.0, empty.slope(), 1e-9);
        assertEquals(0.0, empty.intercept(), 1e-9);
        // One point: flat line through it, quality figures undefined → 0.
        AnalysisMath.Regression single = AnalysisMath.regression(new double[] {3}, new double[] {7});
        assertEquals(0.0, single.slope(), 1e-9);
        assertEquals(7.0, single.intercept(), 1e-9);
        assertEquals(0.0, single.residualSigma(), 1e-9);
    }

    @Test
    void round2RoundsAndNormalisesNegativeZero() {
        assertEquals(1.67, AnalysisMath.round2(1.6666), 1e-9);
        assertEquals(0.0, AnalysisMath.round2(-0.001), 1e-9);
        assertEquals(2.0, AnalysisMath.round2(1.999), 1e-9);
    }
}
