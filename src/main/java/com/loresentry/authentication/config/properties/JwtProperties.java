package com.loresentry.authentication.config.properties;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("auth.jwt")
public record JwtProperties(
        @NotBlank String privateKeyBase64,
        @NotBlank String publicKeyPath,
        @NotBlank String keyId
) {
    @Override public String toString() { return "JwtProperties[REDACTED]"; }
}
