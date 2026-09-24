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
import com.loresentry.authentication.application.port.out.JwtTokens;
import com.loresentry.authentication.application.port.out.OAuthStateStore;
import com.loresentry.authentication.application.port.out.OidcClient;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.application.port.out.SessionStore;
import lombok.RequiredArgsConstructor;

/**
 * 로그인 요청 소모, 공급자 신원 검증, 계정 등록과 토큰 발급을 순서대로 연결한다.
 *
 * <p>계정 변경의 DB 커밋 이후 토큰을 발급하고 활성 세션을 교체한다. 이후 실패가 계정 커밋이나 로그인 요청 소모를 되돌리지는 않는다.
 */
@RequiredArgsConstructor
public final class LoginService implements LoginUseCase {
    private final OAuthRequests oauthRequests;
    private final OidcClient oidcClient;
    private final RegisterIdentityUseCase accountRegistration;
    private final JwtTokens jwtTokens;
    private final SessionStore sessions;

    @Override
    public PreparedLogin prepare() {
        return oauthRequests.prepare();
    }

    @Override
    public LoginResult callback(Callback command) {
        var loginState = consumeLoginRequest(command);
        if (command.error() != null && !command.error().isBlank()) {
            throw new AuthFailure(OAUTH_LOGIN_DENIED, CONSUMED);
        }
        try {
            var identity = oidcClient.exchange(command.code(), loginState);
            // Account registration returns only after the DB transaction commits.
            var user = accountRegistration.register(identity);
            var sid = java.util.UUID.randomUUID();
            var issuedTokens = issueTokens(user.id(), sid);
            sessions.replace(
                    user.id(),
                    new SessionStore.Session(
                            sid,
                            issuedTokens.refreshJti(),
                            issuedTokens.tokens().refreshExpiresAt()));
            return new LoginResult(issuedTokens.tokens(), CONSUMED);
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

    private JwtTokens.Issued issueTokens(java.util.UUID userId, java.util.UUID sid) {
        try {
            return jwtTokens.issue(userId, sid);
        } catch (RuntimeException failure) {
            throw new AuthFailure(INTERNAL_ERROR);
        }
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
