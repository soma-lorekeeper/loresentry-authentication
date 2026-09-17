package com.loresentry.authentication.domain;

public final class DisplayNames {
    private DisplayNames() {}
    public static String initial(String name) {
        if (name == null || name.isBlank()) return "사용자";
        var value = name.strip();
        return length(value) > 50 ? value.substring(0, value.offsetByCodePoints(0, 50)) : value;
    }
    public static String edited(String name) {
        if (name == null) throw new InvalidDisplayName();
        var value = name.strip();
        if (value.isEmpty() || length(value) > 50) throw new InvalidDisplayName();
        return value;
    }
    private static int length(String value) { return value.codePointCount(0, value.length()); }
    public static final class InvalidDisplayName extends RuntimeException {
        public InvalidDisplayName() { super("Invalid display name"); }
    }
}
