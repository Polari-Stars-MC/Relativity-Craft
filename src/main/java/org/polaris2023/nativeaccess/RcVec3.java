package org.polaris2023.nativeaccess;

public record RcVec3(float x, float y, float z) {
    public static final RcVec3 ZERO = new RcVec3(0.0f, 0.0f, 0.0f);
}
