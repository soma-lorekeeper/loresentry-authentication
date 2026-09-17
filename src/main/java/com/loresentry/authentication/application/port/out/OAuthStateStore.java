package com.loresentry.authentication.application.port.out;

import java.time.Instant;
import java.util.Optional;

public interface OAuthStateStore {
    record State(int schemaVersion, String registrationId, String clientId, String redirectUri,
                 String state, String nonce, String codeVerifier, Instant createdAt, Instant expiresAt) {
        @Override public String toString() { return "OAuthState[REDACTED]"; }
    }
    /** False means a confirmed key collision, never a storage failure. */
    boolean create(String loginRequestId, State state);
    Optional<State> find(String loginRequestId);
    Optional<State> consume(String loginRequestId);
}
