package org.polaris2023.relativity.atmosphere;

public record AtmosphereSample(
        double windX,
        double windY,
        double windZ,
        double speed,
        double density,
        double turbulence
) {
}
