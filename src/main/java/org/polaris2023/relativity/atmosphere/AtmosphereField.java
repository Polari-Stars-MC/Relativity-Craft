package org.polaris2023.relativity.atmosphere;

public final class AtmosphereField {
    private final WindFieldBackend backend;

    public AtmosphereField(WindFieldBackend backend) {
        this.backend = backend;
    }

    public AtmosphereSample sample(double x, double y, double z, long worldTime, WeatherState weather) {
        AtmosphereSample base = backend.sample(x, y, z, worldTime);
        double altitudeDensity = densityAtAltitude(y);
        double weatherMultiplier = weather.windMultiplier();
        double speed = base.speed() * weatherMultiplier;
        double length = Math.sqrt(base.windX() * base.windX() + base.windY() * base.windY() + base.windZ() * base.windZ());
        if (length <= 1.0e-9) {
            return new AtmosphereSample(0.0, 0.0, 0.0, 0.0, altitudeDensity, base.turbulence() * weather.turbulenceMultiplier());
        }
        return new AtmosphereSample(
                base.windX() / length * speed,
                base.windY() / length * speed,
                base.windZ() / length * speed,
                speed,
                altitudeDensity,
                base.turbulence() * weather.turbulenceMultiplier()
        );
    }

    static double densityAtAltitude(double y) {
        double seaLevelDensity = 1.225;
        double scaleHeight = 8500.0;
        return seaLevelDensity * Math.exp(-Math.max(0.0, y) / scaleHeight);
    }
}
