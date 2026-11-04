package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.ConsentId;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class TermsAcceptService implements TermsAcceptUseCase {
    /**
     * 동의 요청을 거절한 이유(consent request rejected 줄의 result.reason). 약관 조회({@link TermsQueryService})도
     * 같은 목록을 쓴다. 목록 밖의 값은 other로 남는다.
     */
    static final Set<String> CONSENT_REJECTIONS =
            Set.of(
                    "INVALID_ID",
                    "NOT_FOUND",
                    "ACCOUNT_MISSING",
                    "NOT_CONSUMABLE",
                    "REFRESH_FAILED");

    private final ConsentRequestStore requests;
    private final TermsVersionStore versions;
    private final TermsAcceptanceStore acceptances;
    private final AccountStore accounts;
    private final SessionIdGenerator ids;
    private final LoginSessionStore sessions;
    private final Clock clock;

    public Accepted accept(String rawId, String rawVersion) {
        // 동의 요청 ID는 로그인을 이어 갈 수 있는 값이라 남기지 않는다. 약관 버전은 검증을 마친 뒤 결과 줄에 남긴다
        AppLogger.current().info("terms accept");
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
            AppLogger.current()
                    .warn(
                            "consent request rejected",
                            LogField.errorType(AuthFailure.Reason.CONSENT_REQUEST_INVALID.name()),
                            LogField.resultCode("reason", "INVALID_ID", CONSENT_REJECTIONS));
            throw invalid();
        }
        // 동의 요청을 소모했는지. 실패 줄(terms accept failed)에 남긴다. 소모 호출 도중 실패하면 알 수 없어 null이다
        Boolean consumed = false;
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
                                                                CONSENT_REJECTIONS));
                                        return invalid();
                                    });
            // 동의 요청에서 처음 알게 된 사용자. 이 뒤의 줄(계정·약관·세션 저장소 줄, 결과 줄 request completed)에 user.id가 붙는다
            AppLogger.current().identify(pending.userId());
            if (accounts.findById(pending.userId()).isEmpty()) {
                AppLogger.current()
                        .warn(
                                "consent request rejected",
                                LogField.errorType(
                                        AuthFailure.Reason.CONSENT_REQUEST_INVALID.name()),
                                LogField.resultCode(
                                        "reason", "ACCOUNT_MISSING", CONSENT_REJECTIONS));
                throw invalid();
            }
            var current =
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
            if (!current.id().equals(version) || !pending.termsVersionId().equals(version)) {
                // 409. 화면이 본 버전(요청), 지금 버전, 동의 요청에 묶인 버전을 함께 남겨 어느 쪽이 바뀌었는지 보이게 한다
                AppLogger.current()
                        .warn(
                                "terms accept version mismatch",
                                LogField.errorType(
                                        AuthFailure.Reason.TERMS_VERSION_MISMATCH.name()),
                                LogField.param("terms_version_id", version),
                                LogField.param("current_terms_version_id", current.id()),
                                LogField.param(
                                        "pending_terms_version_id", pending.termsVersionId()));
                throw mismatch();
            }
            consumed = null;
            switch (requests.consume(id, pending.userId(), version)) {
                case INVALID -> {
                    AppLogger.current()
                            .warn(
                                    "consent request rejected",
                                    LogField.errorType(
                                            AuthFailure.Reason.CONSENT_REQUEST_INVALID.name()),
                                    LogField.resultCode(
                                            "reason", "NOT_CONSUMABLE", CONSENT_REJECTIONS));
                    throw invalid();
                }
                case VERSION_MISMATCH -> {
                    // 위 비교를 통과한 뒤 소모하는 사이에 동의 요청의 버전이 바뀌었다(다른 화면이 약관을 다시 읽음)
                    AppLogger.current()
                            .warn(
                                    "terms accept version mismatch",
                                    LogField.errorType(
                                            AuthFailure.Reason.TERMS_VERSION_MISMATCH.name()),
                                    LogField.param("terms_version_id", version),
                                    LogField.param("current_terms_version_id", current.id()),
                                    LogField.param(
                                            "pending_terms_version_id", pending.termsVersionId()));
                    throw mismatch();
                }
                case CONSUMED -> consumed = true;
            }
            acceptances.accept(pending.userId(), version, clock.instant());
            var issued = SessionIssuance.create(pending.userId(), ids, sessions, accounts);
            // 약관 동의로 로그인을 마친 결과 줄. 세션 ID는 남기지 않는다
            AppLogger.current()
                    .info("terms accept completed", LogField.param("terms_version_id", version));
            return new Accepted(issued.id(), issued.expiresAt());
        } catch (AuthFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            // 503으로 바뀌어 원인이 사라지므로 여기서 남긴다. 예외 메시지는 남기지 않고 클래스 이름과 프레임만 남긴다
            AppLogger.current()
                    .warn(
                            "terms accept failed",
                            LogField.errorType(failure.getClass().getName()),
                            LogField.safeStackTrace(failure),
                            LogField.param("consent_consumed", consumed));
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
