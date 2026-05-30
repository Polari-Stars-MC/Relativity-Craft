package org.polaris2023.relativity.nativeaccess;

public record RcBool(boolean value) {
    public static final RcBool FALSE = new RcBool(false);
    public static final RcBool TRUE = new RcBool(true);

    public static RcBool of(boolean value) {
        return value ? TRUE : FALSE;
    }
}
