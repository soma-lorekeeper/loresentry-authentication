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
        // 동의 요청 ID는 로그인을 이어 갈 수 있는 값이라 남기지 않는다(언어는 controller 진입 줄에 있다)
        AppLogger.current().info("terms get");
        ConsentId id;
        try {
            id = new ConsentId(raw);
        } catch (IllegalArgumentException error) {
            AppLogger.current()
                    .warn(
                            "consent request rejected",
                            LogField.errorType(AuthFailure.Reason.CONSENT_REQUEST_INVALID.name()),
                            LogField.resultCode(
                                    "reason", "INVALID_ID", TermsAcceptService.CONSENT_REJECTIONS));
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
                                                        LogField.errorType(
                                                                AuthFailure.Reason
                                                                        .CONSENT_REQUEST_INVALID
                                                                        .name()),
                                                        LogField.resultCode(
                                                                "reason",
                                                                "NOT_FOUND",
                                                                TermsAcceptService
                                                                        .CONSENT_REJECTIONS));
                                        return invalid();
                                    });
            // 동의 요청에서 처음 알게 된 사용자. 이 뒤의 줄(계정·약관·동의 요청 저장소 줄, 결과 줄 request completed)에 user.id가 붙는다
            AppLogger.current().identify(pending.userId());
            if (accounts.findById(pending.userId()).isEmpty()) {
                AppLogger.current()
                        .warn(
                                "consent request rejected",
                                LogField.errorType(
                                        AuthFailure.Reason.CONSENT_REQUEST_INVALID.name()),
                                LogField.resultCode(
                                        "reason",
                                        "ACCOUNT_MISSING",
                                        TermsAcceptService.CONSENT_REJECTIONS));
                throw invalid();
            }
            var version =
                    versions.current(clock.instant())
                            .orElseThrow(
                                    () -> {
                                        AppLogger.current()
                                                .warn(
                                                        "terms current version missing",
                                                        LogField.errorType(
                                                                AuthFailure.Reason.LOGIN_UNAVAILABLE
                                                                        .name()));
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
                                                .warn(
                                                        "consent request rejected",
                                                        LogField.errorType(
                                                                AuthFailure.Reason
                                                                        .CONSENT_REQUEST_INVALID
                                                                        .name()),
                                                        LogField.resultCode(
                                                                "reason",
                                                                "REFRESH_FAILED",
                                                                TermsAcceptService
                                                                        .CONSENT_REJECTIONS));
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
                                                    "locale",
                                                    requested.code(),
                                                    SupportedLocale.CODES),
                                            LogField.param("terms_version_id", version.id()));
                            return version.original();
                        });
    }

    private static AuthFailure invalid() {
        return new AuthFailure(AuthFailure.Reason.CONSENT_REQUEST_INVALID);
    }
}
