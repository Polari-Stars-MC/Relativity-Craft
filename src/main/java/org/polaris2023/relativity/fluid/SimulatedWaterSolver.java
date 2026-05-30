package org.polaris2023.relativity.fluid;

public final class SimulatedWaterSolver {
    private static final double[] WAVE_DIR_X = {0.91, 0.63, -0.18, 0.31, -0.74, 0.08, 0.52};
    private static final double[] WAVE_DIR_Z = {0.41, 0.78, 0.98, -0.95, 0.67, -1.00, -0.85};
    private static final double[] WAVE_LENGTH = {31.0, 18.0, 44.0, 11.0, 67.0, 7.5, 23.0};
    private static final double[] WAVE_SPEED = {0.82, 1.18, 0.55, 1.72, 0.42, 2.25, 1.05};
    private static final double[] WAVE_WEIGHT = {0.42, 0.25, 0.36, 0.10, 0.30, 0.045, 0.14};

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
        double swell = 0.16 + oceanWeight * 0.92;
        for (int i = 0; i < WAVE_LENGTH.length; i++) {
            double waveNumber = Math.PI * 2.0 / WAVE_LENGTH[i];
            double phase = (x * WAVE_DIR_X[i] + z * WAVE_DIR_Z[i]) * waveNumber + time * WAVE_SPEED[i];
            double amplitude = swell * WAVE_WEIGHT[i];
            double sin = Math.sin(phase);
            double cos = Math.cos(phase);
            height += sin * amplitude;
            slopeX += cos * amplitude * waveNumber * WAVE_DIR_X[i];
            slopeZ += cos * amplitude * waveNumber * WAVE_DIR_Z[i];
            double chop = -cos * amplitude * 0.42;
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
        foam = Math.max(foam, clamp((slope - 0.34) / 0.58, 0.0, 1.0) * oceanWeight);
        return new OceanSurfaceSample(
                baseSurfaceY + clamp(height, -1.35, 1.35),
                slopeX,
                slopeZ,
                clamp(chopX, -0.42, 0.42),
                clamp(chopZ, -0.42, 0.42),
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
