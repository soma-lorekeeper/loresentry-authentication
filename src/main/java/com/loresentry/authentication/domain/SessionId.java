package com.loresentry.authentication.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;

/** Canonical opaque credential. Never include its value in diagnostics. */
public record SessionId(String value) {
    public SessionId {
        if (value == null || !value.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("Invalid session ID");
        }
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (bytes.length != 32
                || !Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).equals(value)) {
            throw new IllegalArgumentException("Invalid session ID");
        }
    }

    public String hash() {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    @Override
    public String toString() {
        return "SessionId[redacted]";
    }
}
