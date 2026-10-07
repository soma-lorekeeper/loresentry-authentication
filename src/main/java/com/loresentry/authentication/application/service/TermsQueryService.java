package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.TermsQueryUseCase;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.ConsentId;
import com.loresentry.authentication.domain.SupportedLocale;
import com.loresentry.authentication.domain.TermsText;
import com.loresentry.authentication.domain.TermsVersion;
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

    public TermsView query(String raw, String locale) {
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
            var text = text(version, locale);
            var refreshed =
                    requests.refreshVersion(id, pending.userId(), version.id())
                            .orElseThrow(TermsQueryService::invalid);
            return new TermsView(version, text, refreshed.expiresAt());
        } catch (PortFailure error) {
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        }
    }

    // 지원하지 않는 언어와 번역이 없는 버전은 오류 없이 원문으로 응답한다.
    private TermsText text(TermsVersion version, String locale) {
        var requested = SupportedLocale.find(locale).orElse(TermsVersion.ORIGINAL_LOCALE);
        if (requested == TermsVersion.ORIGINAL_LOCALE) return version.original();
        return versions.translation(version.id(), requested).orElseGet(version::original);
    }

    private static AuthFailure invalid() {
        return new AuthFailure(AuthFailure.Reason.CONSENT_REQUEST_INVALID);
    }
}
