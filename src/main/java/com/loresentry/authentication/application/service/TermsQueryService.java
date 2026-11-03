package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.TermsQueryUseCase;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.ConsentId;
import com.loresentry.authentication.domain.SupportedLocale;
import com.loresentry.authentication.domain.TermsText;
import com.loresentry.authentication.domain.TermsVersion;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.time.Clock;
import java.util.Set;

public final class TermsQueryService implements TermsQueryUseCase {
    /** 동의 요청을 거절한 이유(consent request rejected 줄의 result.reason). 목록 밖의 값은 other로 남는다. */
    private static final Set<String> CONSENT_REJECTIONS =
            Set.of(
                    "INVALID_ID",
                    "NOT_FOUND",
                    "ACCOUNT_MISSING",
                    "NOT_CONSUMABLE",
                    "REFRESH_FAILED");

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
        // 동의 요청 ID는 로그인을 이어 갈 수 있는 값이라 남기지 않는다(언어는 controller 진입 줄에 있다)
        AppLogger.current().info("terms get");
        ConsentId id;
        try {
            id = new ConsentId(raw);
        } catch (IllegalArgumentException error) {
            AppLogger.current()
                    .warn(
                            "consent request rejected",
                            LogField.resultCode("reason", "INVALID_ID", CONSENT_REJECTIONS));
            throw invalid();
        }
        try {
            var pending =
                    requests.find(id)
                            .orElseThrow(
                                    () -> {
                                        AppLogger.current()
                                                .warn(
                                                        "consent request rejected",
                                                        LogField.resultCode(
                                                                "reason",
                                                                "NOT_FOUND",
                                                                CONSENT_REJECTIONS));
                                        return invalid();
                                    });
            if (accounts.findById(pending.userId()).isEmpty()) {
                AppLogger.current()
                        .withUser(pending.userId())
                        .warn(
                                "consent request rejected",
                                LogField.resultCode(
                                        "reason", "ACCOUNT_MISSING", CONSENT_REJECTIONS));
                throw invalid();
            }
            var version =
                    versions.current(clock.instant())
                            .orElseThrow(
                                    () -> {
                                        AppLogger.current().warn("terms current version missing");
                                        return new AuthFailure(
                                                AuthFailure.Reason.LOGIN_UNAVAILABLE);
                                    });
            var text = text(version, locale);
            var refreshed =
                    requests.refreshVersion(id, pending.userId(), version.id())
                            .orElseThrow(
                                    () -> {
                                        // 조회한 뒤 갱신하는 사이에 동의 요청이 만료·소모됐거나 사용자가 맞지 않는다
                                        AppLogger.current()
                                                .withUser(pending.userId())
                                                .warn(
                                                        "consent request rejected",
                                                        LogField.resultCode(
                                                                "reason",
                                                                "REFRESH_FAILED",
                                                                CONSENT_REJECTIONS));
                                        return invalid();
                                    });
            return new TermsView(version, text, refreshed.expiresAt());
        } catch (PortFailure error) {
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        }
    }

    // 지원하지 않는 언어와 번역이 없는 버전은 오류 없이 원문으로 응답한다.
    private TermsText text(TermsVersion version, String locale) {
        var requested = SupportedLocale.find(locale).orElse(TermsVersion.ORIGINAL_LOCALE);
        if (requested == TermsVersion.ORIGINAL_LOCALE) return version.original();
        return versions.translation(version.id(), requested)
                .orElseGet(
                        () -> {
                            // 번역이 없어 원문으로 대신한다. 오류는 아니라 info로 남긴다
                            AppLogger.current()
                                    .info(
                                            "terms translation missing",
                                            LogField.code(
                                                    "locale", requested.code(), Set.of("ko", "en")),
                                            LogField.param("terms_version_id", version.id()));
                            return version.original();
                        });
    }

    private static AuthFailure invalid() {
        return new AuthFailure(AuthFailure.Reason.CONSENT_REQUEST_INVALID);
    }
}
