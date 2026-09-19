package com.loresentry.authentication.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("auth.google")
public record GoogleProperties(
        @NotBlank String clientId,
        @NotBlank String clientSecret,
        @NotBlank String redirectUri
) {
    @Override public String toString() { return "GoogleProperties[REDACTED]"; }
}
