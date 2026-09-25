package com.loresentry.authentication.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.URI;
import java.time.Instant;
import java.util.UUID;

public final class AuthResponses {
    private AuthResponses() {}

    public record PreparedLogin(
            @JsonProperty("authorization_url") URI authorizationUrl,
            @JsonProperty("login_request_id") String loginRequestId,
            @JsonProperty("expires_at") Instant expiresAt) {
        @Override
        public String toString() {
            return "PreparedLogin[REDACTED]";
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Callback(
            @JsonProperty("session_id") String sessionId,
            @JsonProperty("expires_at") Instant expiresAt,
            @JsonProperty("login_request_consumed") Boolean loginRequestConsumed) {
        @Override
        public String toString() {
            return "Callback[REDACTED]";
        }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Profile(
            UUID id, @JsonProperty("display_name") String displayName, String email) {}
}
