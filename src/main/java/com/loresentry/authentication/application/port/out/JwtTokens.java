package com.loresentry.authentication.application.port.out;

import com.loresentry.authentication.application.port.in.TokenPair;
import java.time.Instant;
import java.util.UUID;

public interface JwtTokens {
    record Issued(TokenPair tokens, UUID refreshJti) {}
    record RefreshClaims(UUID userId, UUID jti, Instant expiresAt) {}
    Issued issue(UUID userId);
    /** allowExpired is for revocation only; every other validation remains mandatory. */
    RefreshClaims verifyRefresh(String token, boolean allowExpired);
}
