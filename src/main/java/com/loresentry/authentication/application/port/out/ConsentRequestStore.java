package com.loresentry.authentication.application.port.out;

import com.loresentry.authentication.domain.ConsentId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ConsentRequestStore {
    enum Consumption {
        CONSUMED,
        INVALID,
        VERSION_MISMATCH
    }

    Consumption consume(ConsentId id, UUID userId, UUID termsVersionId);

    record Pending(UUID userId, UUID termsVersionId, Instant createdAt, Instant expiresAt) {}

    Optional<Pending> create(ConsentId id, UUID userId, UUID termsVersionId);

    Optional<Pending> find(ConsentId id);

    Optional<Pending> refreshVersion(ConsentId id, UUID userId, UUID termsVersionId);
}
