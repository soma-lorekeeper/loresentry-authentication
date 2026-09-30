package com.loresentry.authentication.application.port.in;

import com.loresentry.authentication.domain.ConsentId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TermsLoginGate {
    record Required(ConsentId id, Instant expiresAt) {}

    Optional<Required> check(UUID userId);
}
