package com.loresentry.authentication.application.port.in;

import java.time.Instant;

public record TokenPair(String accessToken, Instant accessExpiresAt,
                        String refreshToken, Instant refreshExpiresAt) {
    @Override public String toString() { return "TokenPair[REDACTED]"; }
}
