package com.loresentry.authentication.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.authentication.application.port.in.AccountUseCase;
import com.loresentry.authentication.application.port.in.LoginUseCase;
import com.loresentry.authentication.application.port.in.TokenPair;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public final class AuthResponses {
    private AuthResponses() {}

    public record PreparedLogin(
            @JsonProperty("authorization_url") URI authorizationUrl,
            @JsonProperty("login_request_id") String loginRequestId,
            @JsonProperty("expires_at") Instant expiresAt
    ) {
        public static PreparedLogin from(LoginUseCase.PreparedLogin login) {
            return new PreparedLogin(login.authorizationUrl(), login.loginRequestId(), login.expiresAt());
        }

        @Override public String toString() { return "PreparedLogin[REDACTED]"; }
    }

    public record Tokens(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("access_expires_at") Instant accessExpiresAt,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_expires_at") Instant refreshExpiresAt
    ) {
        public static Tokens from(TokenPair tokens) {
            return new Tokens(tokens.accessToken(), tokens.accessExpiresAt(),
                    tokens.refreshToken(), tokens.refreshExpiresAt());
        }

        @Override public String toString() { return "Tokens[REDACTED]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Callback(
            @JsonProperty("access_token") String accessToken,
            @JsonProperty("access_expires_at") Instant accessExpiresAt,
            @JsonProperty("refresh_token") String refreshToken,
            @JsonProperty("refresh_expires_at") Instant refreshExpiresAt,
            @JsonProperty("login_request_consumed") Boolean loginRequestConsumed
    ) {
        public static Callback from(LoginUseCase.LoginResult login) {
            var tokens = login.tokens();
            Boolean consumed = switch (login.consumption()) {
                case CONSUMED -> true;
                case NOT_CONSUMED -> false;
                case UNKNOWN -> null;
            };
            return new Callback(tokens.accessToken(), tokens.accessExpiresAt(),
                    tokens.refreshToken(), tokens.refreshExpiresAt(), consumed);
        }

        @Override public String toString() { return "Callback[REDACTED]"; }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Profile(
            UUID id,
            @JsonProperty("display_name") String displayName,
            String email
    ) {
        public static Profile from(AccountUseCase.Profile profile) {
            return new Profile(profile.id(), profile.displayName(), profile.email());
        }
    }
}
