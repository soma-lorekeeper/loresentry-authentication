package com.loresentry.authentication.domain;

import java.util.Locale;
import java.util.regex.Pattern;

public final class PasswordAccount {
    public static final String PROVIDER = "password";
    public static final int LOGIN_ID_MIN = 3;
    public static final int LOGIN_ID_MAX = 32;
    public static final int PASSWORD_MIN = 8;
    public static final int PASSWORD_MAX = 72;
    private static final Pattern LOGIN_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{2,31}");

    private PasswordAccount() {}

    public static String loginId(String value) {
        if (value == null) throw new InvalidLoginId();
        var normalized = value.strip().toLowerCase(Locale.ROOT);
        if (!LOGIN_ID.matcher(normalized).matches()) throw new InvalidLoginId();
        return normalized;
    }

    public static String password(String value) {
        if (value == null) throw new InvalidPassword();
        int bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (value.codePointCount(0, value.length()) < PASSWORD_MIN
                || bytes > PASSWORD_MAX
                || value.chars().anyMatch(Character::isWhitespace)) throw new InvalidPassword();
        return value;
    }

    public static final class InvalidLoginId extends RuntimeException {
        public InvalidLoginId() {
            super("Invalid login id");
        }
    }

    public static final class InvalidPassword extends RuntimeException {
        public InvalidPassword() {
            super("Invalid password");
        }
    }
}
