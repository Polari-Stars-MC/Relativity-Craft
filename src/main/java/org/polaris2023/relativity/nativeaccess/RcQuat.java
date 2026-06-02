package org.polaris2023.relativity.nativeaccess;

public record RcQuat(double i, double j, double k, double w) {
    public static final RcQuat IDENTITY = new RcQuat(0.0, 0.0, 0.0, 1.0);
}
