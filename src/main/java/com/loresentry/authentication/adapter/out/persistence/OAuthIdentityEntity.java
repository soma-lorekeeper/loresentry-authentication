package com.loresentry.authentication.adapter.out.persistence;

import com.loresentry.authentication.domain.OAuthIdentity;
import jakarta.persistence.*;

@Entity
@Table(name = "oauth_identities")
public class OAuthIdentityEntity {
    @EmbeddedId private OAuthIdentityId id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false) private UserEntity user;
    @Column(name = "email", length = 320) private String email;
    protected OAuthIdentityEntity() {}
    public OAuthIdentityEntity(OAuthIdentity identity, UserEntity user) {
        id = new OAuthIdentityId(identity.provider(), identity.providerId()); this.user = user; email = identity.email();
    }
    public UserEntity user() { return user; }
    public void updateEmail(String value) { email = value; }
    public OAuthIdentity toDomain() { return new OAuthIdentity(id.provider(), id.providerId(), user.toDomain().id(), email); }
}
