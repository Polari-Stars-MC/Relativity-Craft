package org.polaris2023.relativity.material;

public record PhysicsMaterial(double density, double friction, double restitution, double linearDamping, double angularDamping) {
    public static final PhysicsMaterial DEFAULT = new PhysicsMaterial(1.0, 0.6, 0.2, 0.02, 0.02);

    public PhysicsMaterial {
        if (density <= 0.0) {
            throw new IllegalArgumentException("density must be positive");
        }
        friction = clamp(friction, 0.0, 2.0);
        restitution = clamp(restitution, 0.0, 1.0);
        linearDamping = Math.max(0.0, linearDamping);
        angularDamping = Math.max(0.0, angularDamping);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
