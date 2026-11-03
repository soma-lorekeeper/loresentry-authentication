package com.loresentry.authentication.application.service;

import static com.loresentry.authentication.application.port.in.AuthFailure.Consumption.CONSUMED;
import static com.loresentry.authentication.application.port.in.AuthFailure.Consumption.UNKNOWN;
import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.INTERNAL_ERROR;
import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.LOGIN_UNAVAILABLE;
import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.OAUTH_IDENTITY_INVALID;
import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.OAUTH_LOGIN_DENIED;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.LoginUseCase;
import com.loresentry.authentication.application.port.in.RegisterIdentityUseCase;
import com.loresentry.authentication.application.port.in.TermsLoginGate;
import com.loresentry.authentication.application.port.out.AccountStore;
import com.loresentry.authentication.application.port.out.LoginSessionStore;
import com.loresentry.authentication.application.port.out.OAuthStateStore;
import com.loresentry.authentication.application.port.out.OidcClient;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.application.port.out.SessionIdGenerator;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/**
 * 로그인 요청 소모, 공급자 신원 검증, 계정 등록과 세션 생성을 순서대로 연결한다.
 *
 * <p>계정 변경의 DB 커밋 이후 세션을 생성·교체한다. 이후 실패가 계정 커밋이나 로그인 요청 소모를 되돌리지는 않는다.
 */
@RequiredArgsConstructor
public final class LoginService implements LoginUseCase {
    /**
     * 콜백 error로 남겨도 되는 표준 OAuth·OIDC 오류 코드. 이 값은 브라우저가 보낸 것이라 아무 문자열이나 올 수 있어, 목록 밖의 값은 other로 남긴다.
     */
    private static final Set<String> PROVIDER_ERRORS =
            Set.of(
                    "access_denied",
                    "invalid_request",
                    "unauthorized_client",
                    "unsupported_response_type",
                    "invalid_scope",
                    "server_error",
                    "temporarily_unavailable",
                    "interaction_required",
                    "login_required",
                    "consent_required",
                    "account_selection_required",
                    "admin_policy_enforced");

    private final OAuthRequests oauthRequests;
    private final OidcClient oidcClient;
    private final RegisterIdentityUseCase accountRegistration;
    private final SessionIdGenerator sessionIds;
    private final LoginSessionStore sessions;
    private final TermsLoginGate terms;
    private final AccountStore accounts;

    @Override
    public PreparedLogin prepare() {
        AppLogger.current().info("login prepare");
        return oauthRequests.prepare();
    }

    @Override
    public LoginResult callback(Callback command) {
        // 로그인 요청 ID·OAuth state·code는 로그인 과정의 비밀 값이라 남기지 않는다. error는 표준 코드일 때만 결과 줄에 남긴다
        AppLogger.current().info("login callback");
        var loginState = consumeLoginRequest(command);
        if (command.error() != null && !command.error().isBlank()) {
            // 공급자가 거절한 이유를 결과 줄(request completed)의 error.code로 맡긴다. 목록 밖의 값은 other다
            AppLogger.current()
                    .addToOutcome(
                            LogField.errorCode(
                                    PROVIDER_ERRORS.contains(command.error())
                                            ? command.error()
                                            : "other"));
            throw new AuthFailure(OAUTH_LOGIN_DENIED, CONSUMED);
        }
        try {
            var identity = oidcClient.exchange(command.code(), loginState);
            // Account registration returns only after the DB transaction commits.
            var user = accountRegistration.register(identity);
            var required = terms.check(user.id());
            if (required.isPresent()) {
                // 로그인 결과 줄. 콜백 요청에는 X-User-Id가 없어서, 여기서 처음 알게 된 사용자를 붙인다.
                // 동의 요청 ID는 로그인을 이어 갈 수 있는 값이라 남기지 않는다
                AppLogger.current()
                        .withUser(user.id())
                        .info(
                                "login callback completed",
                                LogField.result("status", LoginStatus.TERMS_REQUIRED));
                return LoginResult.termsRequired(required.get().id(), required.get().expiresAt());
            }
            var result = createSession(user.id());
            // 로그인 결과 줄(사용자 포함). 세션 ID는 인증 수단이라 남기지 않는다
            AppLogger.current()
                    .withUser(user.id())
                    .info("login callback completed", LogField.result("status", result.status()));
            return result;
        } catch (PortFailure failure) {
            var reason =
                    switch (failure.kind()) {
                        case INVALID_IDENTITY -> OAUTH_IDENTITY_INVALID;
                        case INVALID_DATA -> INTERNAL_ERROR;
                        default -> LOGIN_UNAVAILABLE;
                    };
            throw new AuthFailure(reason, CONSUMED);
        } catch (AuthFailure failure) {
            throw new AuthFailure(failure.reason(), CONSUMED);
        } catch (RuntimeException failure) {
            throw new AuthFailure(INTERNAL_ERROR, CONSUMED, failure);
        }
    }

    private LoginResult createSession(java.util.UUID userId) {
        var issued = SessionIssuance.create(userId, sessionIds, sessions, accounts);
        return new LoginResult(issued.id(), issued.expiresAt(), CONSUMED);
    }

    private OAuthStateStore.State consumeLoginRequest(Callback command) {
        try {
            return oauthRequests.consume(command);
        } catch (AuthFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthFailure(INTERNAL_ERROR, UNKNOWN, failure);
        }
    }
}
