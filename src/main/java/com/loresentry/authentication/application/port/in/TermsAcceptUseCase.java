package com.loresentry.authentication.application.port.in;

import com.loresentry.authentication.domain.SessionId;
import java.time.Instant;

public interface TermsAcceptUseCase {
    record Accepted(SessionId sessionId, Instant expiresAt) {
        @Override
        public String toString() {
            return "Accepted[redacted]";
        }
    }

    Accepted accept(String consentRequestId, String termsVersionId);
}
