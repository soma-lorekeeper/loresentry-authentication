package com.loresentry.authentication.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenStore {
    void save(UUID jti, UUID userId, Instant expiresAt);
    /** Atomic GETDEL; Optional.empty means confirmed absence. Never retry this command. */
    Optional<UUID> consume(UUID jti);
    /** Both confirmed deletion and confirmed absence return normally. */
    /** Returns a confirmed deletion/absence or fails within 500 ms, including connection acquisition. */
    void delete(UUID jti);
}
