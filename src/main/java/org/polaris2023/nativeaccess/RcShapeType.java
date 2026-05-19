package org.polaris2023.nativeaccess;

public enum RcShapeType {
    BALL(0),
    CUBOID(1),
    CAPSULE_Y(2),
    CAPSULE_X(3),
    CAPSULE_Z(4),
    CYLINDER(5),
    ROUND_CYLINDER(6),
    CONE(7),
    ROUND_CONE(8),
    ROUND_CUBOID(9);

    private final int nativeValue;

    RcShapeType(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    public int nativeValue() {
        return nativeValue;
    }
}
