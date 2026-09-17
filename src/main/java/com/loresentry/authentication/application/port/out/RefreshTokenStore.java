package com.loresentry.authentication.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenStore {
    void save(UUID jti, UUID userId, Instant expiresAt);
    /** Atomic GETDEL; Optional.empty means confirmed absence. Never retry this command. */
    Optional<UUID> consume(UUID jti);
    /** Both confirmed deletion and confirmed absence return normally. */
    void delete(UUID jti);
}
