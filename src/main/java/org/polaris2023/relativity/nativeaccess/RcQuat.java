package org.polaris2023.relativity.nativeaccess;

public record RcQuat(float i, float j, float k, float w) {
    public static final RcQuat IDENTITY = new RcQuat(0.0f, 0.0f, 0.0f, 1.0f);
}
