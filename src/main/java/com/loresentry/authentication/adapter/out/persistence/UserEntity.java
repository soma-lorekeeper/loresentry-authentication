package com.loresentry.authentication.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Entity
@Table(name = "users")
@org.hibernate.annotations.DynamicUpdate
@Getter
@AllArgsConstructor
public class UserEntity {
    @Id private UUID id;

    @Column(name = "display_name", nullable = false, length = 50)
    private String displayName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    @Column(name = "locale", length = 5)
    private String locale;

    protected UserEntity() {}

    public void rename(String name, Instant time) {
        displayName = name;
        updatedAt = time;
    }

    public void changeLocale(String value, Instant time) {
        if (Objects.equals(locale, value)) return;
        locale = value;
        updatedAt = time;
    }

    public void touch(Instant time) {
        updatedAt = time;
    }
}
