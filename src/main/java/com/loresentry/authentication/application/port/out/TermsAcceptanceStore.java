package com.loresentry.authentication.application.port.out;

import java.time.Instant;
import java.util.UUID;

/** Persisted consent only; writes return after commit and preserve the first acceptance time. */
public interface TermsAcceptanceStore {
    boolean hasAccepted(UUID userId, UUID termsVersionId);

    void accept(UUID userId, UUID termsVersionId, Instant acceptedAt);
}
