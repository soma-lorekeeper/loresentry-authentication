package com.loresentry.authentication.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public final class AuthRequests {
    private AuthRequests() {}

    public record Prepare() {}

    public record Callback(
            @JsonProperty("login_request_id") @NotBlank String loginRequestId,
            @NotBlank String state,
            String code,
            String error) {
        @AssertTrue
        @JsonIgnore
        public boolean isValidOutcome() {
            return (code != null && !code.isBlank() && error == null)
                    || (error != null && !error.isBlank() && code == null);
        }

        @Override
        public String toString() {
            return "Callback[REDACTED]";
        }
    }

    public record Session(@JsonProperty("session_id") String sessionId) {
        @Override
        public String toString() {
            return "Session[REDACTED]";
        }
    }

    // Blank/long names remain domain failures (INVALID_DISPLAY_NAME), not malformed requests.
    public record Rename(@JsonProperty("display_name") @NotNull String displayName) {}
}
