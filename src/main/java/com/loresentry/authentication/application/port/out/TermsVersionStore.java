package com.loresentry.authentication.application.port.out;

import com.loresentry.authentication.domain.TermsVersion;
import java.time.Instant;
import java.util.Optional;

public interface TermsVersionStore {
    /** Latest published and effective SERVICE_TERMS at the supplied server time. */
    Optional<TermsVersion> current(Instant now);
}
