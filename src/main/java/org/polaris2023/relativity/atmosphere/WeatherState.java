package org.polaris2023.relativity.atmosphere;

public enum WeatherState {
    CLEAR(1.0, 1.0),
    RAIN(1.25, 1.6),
    THUNDER(1.65, 2.4);

    private final double windMultiplier;
    private final double turbulenceMultiplier;

    WeatherState(double windMultiplier, double turbulenceMultiplier) {
        this.windMultiplier = windMultiplier;
        this.turbulenceMultiplier = turbulenceMultiplier;
    }

    public double windMultiplier() {
        return windMultiplier;
    }

    public double turbulenceMultiplier() {
        return turbulenceMultiplier;
    }
}
