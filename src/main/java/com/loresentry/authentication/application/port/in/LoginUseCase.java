package com.loresentry.authentication.application.port.in;

import java.net.URI;
import java.time.Instant;

public interface LoginUseCase {
    PreparedLogin prepare();
    LoginResult callback(Callback command);

    record PreparedLogin(URI authorizationUrl, String loginRequestId, Instant expiresAt) {
        @Override public String toString() { return "PreparedLogin[REDACTED]"; }
    }
    record Callback(String loginRequestId, String state, String code, String error) {
        @Override public String toString() { return "Callback[REDACTED]"; }
    }
    record LoginResult(TokenPair tokens, AuthFailure.Consumption consumption) {}
}
