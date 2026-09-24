package com.loresentry.authentication.adapter.out.persistence;

import jakarta.persistence.*;
import java.time.Instant;
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

    protected UserEntity() {}

    public void rename(String name, Instant time) {
        displayName = name;
        updatedAt = time;
    }

    public void touch(Instant time) {
        updatedAt = time;
    }
}
