package com.loresentry.authentication.adapter.out.persistence;

import com.loresentry.authentication.domain.User;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserEntity {
    @Id private UUID id;
    @Column(name = "display_name", nullable = false, length = 50) private String displayName;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    protected UserEntity() {}
    public UserEntity(User user) {
        id = user.id(); displayName = user.displayName(); createdAt = user.createdAt(); updatedAt = user.updatedAt();
    }
    public User toDomain() { return new User(id, displayName, createdAt, updatedAt); }
    public void rename(String name, Instant time) { displayName = name; updatedAt = time; }
    public void touch(Instant time) { updatedAt = time; }
}
