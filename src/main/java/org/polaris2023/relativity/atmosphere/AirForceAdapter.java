package org.polaris2023.relativity.atmosphere;

public final class AirForceAdapter {
    private AirForceAdapter() {
    }

    public static ForceSample drag(
            AtmosphereSample atmosphere,
            double velocityX,
            double velocityY,
            double velocityZ,
            double dragCoefficient,
            double referenceArea
    ) {
        double rx = velocityX - atmosphere.windX();
        double ry = velocityY - atmosphere.windY();
        double rz = velocityZ - atmosphere.windZ();
        double relativeSpeed = Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (relativeSpeed <= 1.0e-9) {
            return new ForceSample(0.0, 0.0, 0.0);
        }
        double force = -0.5 * atmosphere.density() * relativeSpeed * relativeSpeed * dragCoefficient * referenceArea;
        return new ForceSample(force * rx / relativeSpeed, force * ry / relativeSpeed, force * rz / relativeSpeed);
    }
}
