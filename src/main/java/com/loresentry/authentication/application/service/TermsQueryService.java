package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.TermsQueryUseCase;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.ConsentId;
import java.time.Clock;

public final class TermsQueryService implements TermsQueryUseCase {
    private final ConsentRequestStore requests;
    private final TermsVersionStore versions;
    private final AccountStore accounts;
    private final Clock clock;

    public TermsQueryService(
            ConsentRequestStore requests,
            TermsVersionStore versions,
            AccountStore accounts,
            Clock clock) {
        this.requests = requests;
        this.versions = versions;
        this.accounts = accounts;
        this.clock = clock;
    }

    public TermsView query(String raw) {
        ConsentId id;
        try {
            id = new ConsentId(raw);
        } catch (IllegalArgumentException error) {
            throw invalid();
        }
        try {
            var pending = requests.find(id).orElseThrow(TermsQueryService::invalid);
            if (accounts.findById(pending.userId()).isEmpty()) throw invalid();
            var version =
                    versions.current(clock.instant())
                            .orElseThrow(
                                    () -> new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE));
            var refreshed =
                    requests.refreshVersion(id, pending.userId(), version.id())
                            .orElseThrow(TermsQueryService::invalid);
            return new TermsView(version, refreshed.expiresAt());
        } catch (PortFailure error) {
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        }
    }

    private static AuthFailure invalid() {
        return new AuthFailure(AuthFailure.Reason.CONSENT_REQUEST_INVALID);
    }
}
