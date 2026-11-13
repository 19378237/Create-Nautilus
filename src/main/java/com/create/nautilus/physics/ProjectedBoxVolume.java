package com.create.nautilus.physics;

import java.util.Arrays;

/** Stable CDF of the projection of a uniform box (or face) onto one axis. */
public final class ProjectedBoxVolume {
    private ProjectedBoxVolume() {}

    /**
     * Fraction below a plane at {@code distanceFromMinimum} above the lowest corner.
     * Zero widths are exact lower-dimensional projections. The largest uniform variable is
     * integrated last, avoiding subtraction of almost equal cubes near an axis-aligned pose.
     */
    public static double fractionBelow(final double distanceFromMinimum, final double... widths) {
        if (widths.length > 3) throw new IllegalArgumentException("At most three box axes");
        final double[] w = new double[widths.length];
        int count = 0;
        for (final double width : widths) {
            if (width > 0.0D) w[count++] = width;
        }
        if (count == 0) return distanceFromMinimum >= 0.0D ? 1.0D : 0.0D;
        Arrays.sort(w, 0, count);
        final double scale = w[count - 1];
        double sum = 0.0D;
        double compensation = 0.0D;
        double simpleSum = 0.0D;
        for (int i = 0; i < count; i++) {
            w[i] /= scale;
            // Match DoubleStream.sum(), including its compensated rounding.
            simpleSum += w[i];
            final double corrected = w[i] - compensation;
            final double next = sum + corrected;
            compensation = (next - sum) - corrected;
            sum = next;
        }
        final double compensated = sum - compensation;
        final double total = Double.isNaN(compensated) && Double.isInfinite(simpleSum)
                ? simpleSum : compensated;
        final double t = distanceFromMinimum / scale;
        if (t <= 0.0D) return 0.0D;
        if (t >= total) return 1.0D;
        // Reflect the upper half; all polynomial work is performed on the well-conditioned tail.
        final boolean upper = t > total * 0.5D;
        final double x = upper ? total - t : t;
        final double result;
        if (count == 1) {
            result = x;
        } else if (count == 2) {
            result = x <= w[0] ? 0.5D * (x / w[0]) * (x / w[1])
                    : (x - w[0] * 0.5D) / w[1];
        } else {
            // The largest width is one after normalization.
            result = integratedTwoUniforms(x, w[0], w[1])
                    - integratedTwoUniforms(x - 1.0D, w[0], w[1]);
        }
        return Math.clamp(upper ? 1.0D - result : result, 0.0D, 1.0D);
    }

    private static double integratedTwoUniforms(final double t, final double a, final double b) {
        if (t <= 0.0D) return 0.0D;
        if (t <= a) return (t / a) * (t / b) * t / 6.0D;
        if (t <= b) return (t * t - a * t + a * a / 3.0D) / (2.0D * b);
        final double tail = a + b - t;
        return t - (a + b) * 0.5D
                + (tail > 0.0D ? (tail / a) * (tail / b) * tail / 6.0D : 0.0D);
    }
}
