package com.loresentry.authentication.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.loresentry.authentication.domain.Acquisition;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;

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

    // ko·en 외의 값은 애플리케이션이 INVALID_REQUEST로 거절한다.
    public record ChangeLocale(@NotNull String locale) {}

    public record RecordAcquisition(
            @JsonProperty("utm_source") @Size(max = 100) @Pattern(regexp = PRINTABLE)
                    String utmSource,
            @JsonProperty("utm_medium") @Size(max = 100) @Pattern(regexp = PRINTABLE)
                    String utmMedium,
            @JsonProperty("utm_campaign") @Size(max = 100) @Pattern(regexp = PRINTABLE)
                    String utmCampaign,
            @JsonProperty("utm_term") @Size(max = 200) @Pattern(regexp = PRINTABLE) String utmTerm,
            @JsonProperty("utm_content") @Size(max = 100) @Pattern(regexp = PRINTABLE)
                    String utmContent,
            @JsonProperty("click_id") @Size(max = 255) @Pattern(regexp = "[A-Za-z0-9_-]+")
                    String clickId,
            @JsonProperty("click_id_type") @Pattern(regexp = "gclid|gbraid|wbraid")
                    String clickIdType,
            @JsonProperty("referrer_host")
                    @Size(max = 253)
                    @Pattern(regexp = "[a-z0-9-]+(\\.[a-z0-9-]+)*")
                    String referrerHost,
            @JsonProperty("landing_path") @Size(max = 255) @Pattern(regexp = "/[^?#\\s\\p{Cntrl}]*")
                    String landingPath,
            @JsonProperty("landed_at") Instant landedAt) {
        private static final String PRINTABLE = "[^\\p{Cntrl}]*";

        @AssertTrue
        @JsonIgnore
        public boolean isClickIdTyped() {
            return (clickId == null) == (clickIdType == null);
        }

        public Acquisition toAcquisition() {
            return new Acquisition(
                    utmSource,
                    utmMedium,
                    utmCampaign,
                    utmTerm,
                    utmContent,
                    clickId,
                    clickIdType,
                    referrerHost,
                    landingPath,
                    landedAt);
        }
    }
}
