package com.loresentry.authentication.adapter.out.persistence;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Entity
@Table(name = "oauth_identities")
@Getter
@AllArgsConstructor
public class OAuthIdentityEntity {
    @EmbeddedId private OAuthIdentityId id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserEntity user;

    @Column(name = "email", length = 320)
    private String email;

    protected OAuthIdentityEntity() {}

    public void updateEmail(String value) {
        email = value;
    }
}
