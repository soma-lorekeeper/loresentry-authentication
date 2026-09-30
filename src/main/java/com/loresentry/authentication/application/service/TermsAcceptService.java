package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.ConsentId;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class TermsAcceptService implements TermsAcceptUseCase {
    private final ConsentRequestStore requests;
    private final TermsVersionStore versions;
    private final TermsAcceptanceStore acceptances;
    private final AccountStore accounts;
    private final SessionIdGenerator ids;
    private final LoginSessionStore sessions;
    private final Clock clock;

    public Accepted accept(String rawId, String rawVersion) {
        UUID version;
        try {
            version = UUID.fromString(rawVersion);
            if (!version.toString().equals(rawVersion)) throw new IllegalArgumentException();
        } catch (RuntimeException error) {
            throw new AuthFailure(AuthFailure.Reason.INVALID_REQUEST);
        }
        ConsentId id;
        try {
            id = new ConsentId(rawId);
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
        try {
            var pending = requests.find(id).orElseThrow(TermsAcceptService::invalid);
            if (accounts.findById(pending.userId()).isEmpty()) throw invalid();
            var current =
                    versions.current(clock.instant())
                            .orElseThrow(
                                    () -> new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE));
            if (!current.id().equals(version) || !pending.termsVersionId().equals(version))
                throw mismatch();
            switch (requests.consume(id, pending.userId(), version)) {
                case INVALID -> throw invalid();
                case VERSION_MISMATCH -> throw mismatch();
                case CONSUMED -> {}
            }
            acceptances.accept(pending.userId(), version, clock.instant());
            var issued = SessionIssuance.create(pending.userId(), ids, sessions, accounts);
            return new Accepted(issued.id(), issued.expiresAt());
        } catch (AuthFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        }
    }

    private static AuthFailure invalid() {
        return new AuthFailure(AuthFailure.Reason.CONSENT_REQUEST_INVALID);
    }

    private static AuthFailure mismatch() {
        return new AuthFailure(AuthFailure.Reason.TERMS_VERSION_MISMATCH);
    }
}
