package org.polaris2023.relativity.fluid;

public final class SimulatedWaterSolver {
    private static final double[] WAVE_DIR_X = {0.91, 0.63, -0.18, 0.31, -0.74, 0.08, 0.52, -0.46, 0.78};
    private static final double[] WAVE_DIR_Z = {0.41, 0.78, 0.98, -0.95, 0.67, -1.00, -0.85, -0.89, 0.62};
    private static final double[] WAVE_LENGTH = {52.0, 31.0, 86.0, 19.0, 128.0, 11.5, 42.0, 7.5, 15.0};
    private static final double[] WAVE_SPEED = {0.62, 0.92, 0.38, 1.24, 0.25, 1.92, 0.76, 2.45, 1.55};
    private static final double[] WAVE_WEIGHT = {0.46, 0.30, 0.36, 0.13, 0.28, 0.055, 0.18, 0.035, 0.08};

    private SimulatedWaterSolver() {
    }

    public static OceanSurfaceSample sample(
            double baseSurfaceY,
            double x,
            double z,
            long gameTime,
            double oceanWeight,
            double localHeight,
            double localSlopeX,
            double localSlopeZ,
            double localFoam,
            double wakeStrength,
            double wakeDirectionX,
            double wakeDirectionZ
    ) {
        double time = gameTime * 0.065;
        double height = localHeight;
        double slopeX = localSlopeX;
        double slopeZ = localSlopeZ;
        double foam = localFoam;
        double chopX = 0.0;
        double chopZ = 0.0;
        double swell = 0.12 + oceanWeight * 1.05;
        for (int i = 0; i < WAVE_LENGTH.length; i++) {
            double waveNumber = Math.PI * 2.0 / WAVE_LENGTH[i];
            double phase = (x * WAVE_DIR_X[i] + z * WAVE_DIR_Z[i]) * waveNumber + time * WAVE_SPEED[i];
            double amplitude = swell * WAVE_WEIGHT[i];
            double sin = Math.sin(phase);
            double cos = Math.cos(phase);
            double crest = Math.max(0.0, sin);
            double fine = Math.sin(phase * 2.17 + i * 1.9) * amplitude * 0.075;
            double shapedHeight = sin * amplitude + crest * crest * amplitude * 0.16 + fine;
            double shapedSlope = (cos + crest * cos * 0.32 + Math.cos(phase * 2.17 + i * 1.9) * 0.16275) * amplitude * waveNumber;
            height += shapedHeight;
            slopeX += shapedSlope * WAVE_DIR_X[i];
            slopeZ += shapedSlope * WAVE_DIR_Z[i];
            double chop = -cos * amplitude * (0.48 + crest * 0.18);
            chopX += WAVE_DIR_X[i] * chop;
            chopZ += WAVE_DIR_Z[i] * chop;
        }

        if (wakeStrength > 0.0) {
            double wakeLength = Math.max(1.0, wakeStrength * 12.0);
            double wakeDirectionLength = Math.sqrt(wakeDirectionX * wakeDirectionX + wakeDirectionZ * wakeDirectionZ);
            double dirX = wakeDirectionLength <= 1.0E-6 ? 0.0 : wakeDirectionX / wakeDirectionLength;
            double dirZ = wakeDirectionLength <= 1.0E-6 ? 0.0 : wakeDirectionZ / wakeDirectionLength;
            double tailX = x - dirX * wakeLength;
            double tailZ = z - dirZ * wakeLength;
            double tailDistance = Math.sqrt((x - tailX) * (x - tailX) + (z - tailZ) * (z - tailZ));
            double wake = Math.sin(tailDistance * 1.65 - time * 7.0) * Math.exp(-tailDistance * 0.38) * wakeStrength;
            height += wake;
            foam = Math.max(foam, clamp(wakeStrength * 0.9, 0.0, 1.0));
        }

        double slope = Math.sqrt(slopeX * slopeX + slopeZ * slopeZ);
        foam = Math.max(foam, clamp((slope - 0.28) / 0.56, 0.0, 1.0) * oceanWeight);
        return new OceanSurfaceSample(
                baseSurfaceY + clamp(height, -1.65, 1.65),
                slopeX,
                slopeZ,
                clamp(chopX, -0.62, 0.62),
                clamp(chopZ, -0.62, 0.62),
                clamp(foam, 0.0, 1.0)
        );
    }

    public static OceanSurfaceSample sampleOceanOnly(
            double baseSurfaceY,
            double x,
            double z,
            long gameTime,
            double oceanWeight
    ) {
        return sample(baseSurfaceY, x, z, gameTime, oceanWeight, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public record OceanSurfaceSample(double surfaceY, double slopeX, double slopeZ, double chopX, double chopZ, double foam) {
    }
}
