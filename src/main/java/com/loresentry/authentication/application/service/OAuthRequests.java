package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.LoginUseCase.Callback;
import com.loresentry.authentication.application.port.in.LoginUseCase.PreparedLogin;
import com.loresentry.authentication.application.port.out.OAuthStateStore;
import com.loresentry.authentication.application.port.out.OidcClient;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.domain.OAuthSecrets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;

/**
 * 로그인 요청의 임시 상태 생성과 콜백의 요청 검증·소모를 담당한다.
 *
 * <p>공급자 코드 교환과 계정·토큰 처리는 {@link LoginService}가 이어서 수행한다.
 */
@RequiredArgsConstructor
public final class OAuthRequests {
    private static final int STATE_SCHEMA_VERSION = 1;
    private static final int LOGIN_REQUEST_TTL_SECONDS = 300;
    private static final int MAX_REQUEST_ID_ATTEMPTS = 5;

    private final OAuthStateStore stateStore;
    private final OidcClient oidcClient;
    private final Clock clock;
    private final SecureRandom random;

    /**
     * state, nonce, PKCE 검증 값을 생성하고 유효 기간이 300초인 요청을 저장한다.
     *
     * <p>요청 식별자 충돌은 최대 5번 시도하며, 기존 요청을 덮어쓰지 않는다.
     *
     * @return 저장한 요청의 식별자, 만료 시각과 인증 URL
     * @throws AuthFailure 외부 연동에 실패하거나 식별자 충돌이 반복되는 경우
     */
    public PreparedLogin prepare() {
        try {
            var loginState = createLoginState();
            return saveLoginRequest(loginState);
        } catch (PortFailure failure) {
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        }
    }

    /**
     * 콜백 입력과 저장 상태를 검증하고 요청을 한 번 소모한다.
     *
     * <p>삭제 전 조회한 값과 실제로 삭제하며 받은 값 모두를 검증한다. 동시 콜백에서는 하나만 요청을 얻을 수 있으며, 삭제 후 검증에 실패해도 요청은 복원하지 않는다.
     *
     * @param callback 요청 식별자, state와 code 또는 error를 담은 콜백
     * @return 검증을 통과한 소모된 요청 상태
     * @throws AuthFailure 입력·상태 검증이나 저장소 호출에 실패한 경우. 소모 여부를 함께 전달
     */
    public OAuthStateStore.State consume(Callback callback) {
        validateCallbackInput(callback);
        var notConsumed = AuthFailure.Consumption.NOT_CONSUMED;
        OidcClient.Settings settings;
        OAuthStateStore.State storedState;
        try {
            settings = oidcClient.settings();
            storedState =
                    stateStore
                            .find(callback.loginRequestId())
                            .orElseThrow(() -> invalid(notConsumed));
        } catch (PortFailure failure) {
            throw storageFailure(failure, notConsumed);
        }
        validateStoredState(storedState, callback.state(), settings, notConsumed);

        var consumedState = consumeStoredState(callback.loginRequestId());
        validateStoredState(
                consumedState, callback.state(), settings, AuthFailure.Consumption.CONSUMED);
        return consumedState;
    }

    private OAuthStateStore.State createLoginState() {
        var settings = oidcClient.settings();
        var createdAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var oauthState = OAuthSecrets.generate(random);
        var nonce = OAuthSecrets.generate(random);
        var codeVerifier = OAuthSecrets.generate(random);
        var expiresAt = createdAt.plusSeconds(LOGIN_REQUEST_TTL_SECONDS);
        return new OAuthStateStore.State(
                STATE_SCHEMA_VERSION,
                settings.registrationId(),
                settings.clientId(),
                settings.redirectUri(),
                oauthState,
                nonce,
                codeVerifier,
                createdAt,
                expiresAt);
    }

    private PreparedLogin saveLoginRequest(OAuthStateStore.State loginState) {
        // Collisions cannot overwrite another request; cap attempts if the entropy source is
        // broken.
        for (int attempt = 0; attempt < MAX_REQUEST_ID_ATTEMPTS; attempt++) {
            var loginRequestId = OAuthSecrets.generate(random);
            if (stateStore.create(loginRequestId, loginState)) {
                var authorizationUrl = oidcClient.authorizationUrl(loginState);
                return new PreparedLogin(authorizationUrl, loginRequestId, loginState.expiresAt());
            }
        }
        throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
    }

    private void validateCallbackInput(Callback callback) {
        if (callback == null
                || !OAuthSecrets.valid(callback.loginRequestId())
                || !OAuthSecrets.valid(callback.state())
                || (isBlank(callback.code()) == isBlank(callback.error()))) {
            throw invalid(AuthFailure.Consumption.NOT_CONSUMED);
        }
    }

    private OAuthStateStore.State consumeStoredState(String loginRequestId) {
        try {
            return stateStore
                    .consume(loginRequestId)
                    .orElseThrow(() -> invalid(AuthFailure.Consumption.NOT_CONSUMED));
        } catch (PortFailure failure) {
            var consumption =
                    switch (failure.execution()) {
                        case NOT_EXECUTED -> AuthFailure.Consumption.NOT_CONSUMED;
                        case EXECUTED -> AuthFailure.Consumption.CONSUMED;
                        case UNKNOWN -> AuthFailure.Consumption.UNKNOWN;
                    };
            throw storageFailure(failure, consumption);
        }
    }

    private void validateStoredState(
            OAuthStateStore.State storedState,
            String callbackState,
            OidcClient.Settings settings,
            AuthFailure.Consumption consumption) {
        if (storedState.schemaVersion() != STATE_SCHEMA_VERSION
                || !callbackState.equals(storedState.state())
                || !settings.registrationId().equals(storedState.registrationId())
                || !settings.clientId().equals(storedState.clientId())
                || !settings.redirectUri().equals(storedState.redirectUri())
                || !clock.instant().isBefore(storedState.expiresAt())
                || storedState.createdAt().isAfter(clock.instant())) {
            throw invalid(consumption);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private AuthFailure invalid(AuthFailure.Consumption consumption) {
        return new AuthFailure(AuthFailure.Reason.OAUTH_REQUEST_INVALID, consumption);
    }

    private AuthFailure storageFailure(PortFailure failure, AuthFailure.Consumption consumption) {
        var reason =
                failure.kind() == PortFailure.Kind.INVALID_DATA
                        ? AuthFailure.Reason.OAUTH_REQUEST_INVALID
                        : AuthFailure.Reason.LOGIN_UNAVAILABLE;
        return new AuthFailure(reason, consumption);
    }
}
