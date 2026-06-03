package org.polaris2023.relativity.nativeaccess;

public record RcVec3(double x, double y, double z) {
    public static final RcVec3 ZERO = new RcVec3(0.0, 0.0, 0.0);
}
