package de.buerokratiecopilot.agents.analysis;

/**
 * Pure arithmetic for the analysis agents: mean, sample standard deviation, z-scores and
 * least-squares linear regression.
 *
 * <p>Plain doubles, no I/O and no state, so every calculation can be unit-tested exactly.
 * Callers guard the minimum number of points they need; the methods are nevertheless defined
 * on small inputs and return neutral values (never NaN or Infinity) instead of failing.</p>
 */
public final class AnalysisMath {

    private static final double EPSILON = 1e-9;

    private AnalysisMath() {
    }

    /** Arithmetic mean; {@code 0} for an empty input. */
    public static double mean(double[] values) {
        if (values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    /** Sample standard deviation (denominator n-1); {@code 0} when there are fewer than two values. */
    public static double sampleStdDev(double[] values) {
        int n = values.length;
        if (n < 2) {
            return 0.0;
        }
        double mean = mean(values);
        double squares = 0.0;
        for (double value : values) {
            double delta = value - mean;
            squares += delta * delta;
        }
        return Math.sqrt(squares / (n - 1));
    }

    /** How many sigmas a value lies away from the mean; {@code 0} when sigma is (almost) zero. */
    public static double zScore(double value, double mean, double sigma) {
        if (sigma < EPSILON) {
            return 0.0;
        }
        return (value - mean) / sigma;
    }

    /**
     * Least-squares line through the points {@code (x[i], y[i])}.
     *
     * @return the fitted line plus quality figures: {@code r2} (coefficient of determination,
     *         0 when the variance of y is zero or the line explains nothing) and
     *         {@code residualSigma} (the residual standard error that feeds the forecast band;
     *         defined only for more than two points, else 0)
     */
    public static Regression regression(double[] x, double[] y) {
        int n = Math.min(x.length, y.length);
        if (n == 0) {
            return new Regression(0.0, 0.0, 0.0, 0.0);
        }
        if (n == 1) {
            return new Regression(0.0, y[0], 0.0, 0.0);
        }
        double[] xs = new double[n];
        double[] ys = new double[n];
        System.arraycopy(x, 0, xs, 0, n);
        System.arraycopy(y, 0, ys, 0, n);

        double meanX = mean(xs);
        double meanY = mean(ys);
        double sxx = 0.0;
        double sxy = 0.0;
        double syy = 0.0;
        for (int i = 0; i < n; i++) {
            double dx = xs[i] - meanX;
            double dy = ys[i] - meanY;
            sxx += dx * dx;
            sxy += dx * dy;
            syy += dy * dy;
        }
        double slope = sxx < EPSILON ? 0.0 : sxy / sxx;
        double intercept = meanY - slope * meanX;
        double sse = 0.0;
        for (int i = 0; i < n; i++) {
            double residual = ys[i] - (slope * xs[i] + intercept);
            sse += residual * residual;
        }
        double r2 = syy < EPSILON ? 0.0 : Math.max(0.0, 1.0 - sse / syy);
        double residualSigma = n > 2 ? Math.sqrt(sse / (n - 2)) : 0.0;
        return new Regression(slope, intercept, r2, residualSigma);
    }

    /** The value of the fitted line at {@code x}. */
    public static double predict(Regression regression, double x) {
        return regression.slope() * x + regression.intercept();
    }

    /** Round to two decimal places for display; normalises {@code -0.0} to {@code 0.0}. */
    public static double round2(double value) {
        double rounded = Math.round(value * 100.0) / 100.0;
        return rounded == 0.0 ? 0.0 : rounded;
    }

    /** A fitted line plus the quality figures used in reports and caveats. */
    public record Regression(double slope, double intercept, double r2, double residualSigma) {
    }
}
