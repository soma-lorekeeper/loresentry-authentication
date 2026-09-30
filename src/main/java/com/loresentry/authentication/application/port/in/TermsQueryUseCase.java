package com.loresentry.authentication.application.port.in;

import com.loresentry.authentication.domain.TermsVersion;
import java.time.Instant;

public interface TermsQueryUseCase {
    record TermsView(TermsVersion terms, Instant expiresAt) {}

    TermsView query(String consentRequestId);
}
