package com.loresentry.authentication.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.Getter;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
@Getter
public class OAuthIdentityId implements Serializable {
    @Column(name = "provider", nullable = false, length = 32) private String provider;
    @Column(name = "provider_id", nullable = false, length = 255) private String providerId;
    protected OAuthIdentityId() {}
    public OAuthIdentityId(String provider, String providerId) { this.provider = provider; this.providerId = providerId; }
    @Override public boolean equals(Object other) {
        return other instanceof OAuthIdentityId id && Objects.equals(provider, id.provider) && Objects.equals(providerId, id.providerId);
    }
    @Override public int hashCode() { return Objects.hash(provider, providerId); }
}
