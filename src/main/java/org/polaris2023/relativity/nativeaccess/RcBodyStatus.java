package org.polaris2023.relativity.nativeaccess;

public enum RcBodyStatus {
    DYNAMIC(0),
    FIXED(1),
    KINEMATIC_POSITION_BASED(2),
    KINEMATIC_VELOCITY_BASED(3);

    private final int nativeValue;

    RcBodyStatus(int nativeValue) {
        this.nativeValue = nativeValue;
    }

    public int nativeValue() {
        return nativeValue;
    }

    public static RcBodyStatus fromNative(int value) {
        for (RcBodyStatus status : values()) {
            if (status.nativeValue == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("Unknown RcBodyStatus native value: " + value);
    }
}
