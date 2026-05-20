package org.polaris2023.relativity.atmosphere;

public final class WindNinjaFastNoiseBackend implements WindFieldBackend {
    private final long seed;

    public WindNinjaFastNoiseBackend(long seed) {
        this.seed = seed;
    }

    @Override
    public AtmosphereSample sample(double x, double y, double z, long worldTime) {
        double phase = (seed * 0.000001) + worldTime * 0.0007;
        double windX = Math.sin(x * 0.002 + phase) * 8.0 + Math.sin(z * 0.011 + phase * 3.0) * 2.0;
        double windZ = Math.cos(z * 0.002 - phase) * 8.0 + Math.cos(x * 0.013 - phase * 2.0) * 2.0;
        double windY = Math.sin((x + z) * 0.001 + phase) * 0.4;
        double speed = Math.sqrt(windX * windX + windY * windY + windZ * windZ);
        double turbulence = Math.min(1.0, Math.abs(Math.sin((x * 12.9898 + z * 78.233 + worldTime) * 0.001)));
        return new AtmosphereSample(windX, windY, windZ, speed, AtmosphereField.densityAtAltitude(y), turbulence);
    }
}
