package com.loresentry.authentication.application.port.out;

import com.loresentry.authentication.domain.SessionId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Schema v2 login index; expiration is owned by Redis, not the application clock. */
public interface LoginSessionStore {
    /** Empty means a confirmed ID collision with no mutation. Failures are never retried. */
    Optional<Instant> replace(UUID userId, SessionId id);
}
