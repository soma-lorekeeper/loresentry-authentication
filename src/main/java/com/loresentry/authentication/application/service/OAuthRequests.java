package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.LoginUseCase.Callback;
import com.loresentry.authentication.application.port.in.LoginUseCase.PreparedLogin;
import com.loresentry.authentication.application.port.out.OAuthStateStore;
import com.loresentry.authentication.application.port.out.OidcClient;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.domain.OAuthSecrets;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import com.loresentry.authentication.logging.LogLayer;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/**
 * 로그인 요청의 임시 상태 생성과 콜백의 요청 검증·소모를 담당한다.
 *
 * <p>공급자 코드 교환과 계정·토큰 처리는 {@link LoginService}가 이어서 수행한다.
 */
// log.layer: LoginService가 부르는 로그인 요청 처리 단계라 service 레이어다(클래스 이름 끝 규칙에 맞지 않아 직접 지정)
@LogLayer("service")
@RequiredArgsConstructor
public final class OAuthRequests {
    private static final int STATE_SCHEMA_VERSION = 1;
    private static final int LOGIN_REQUEST_TTL_SECONDS = 300;
    private static final int MAX_REQUEST_ID_ATTEMPTS = 5;

    /**
     * 로그인 요청을 거절한 이유(login request rejected 줄의 result.reason). 응답은 모두 OAUTH_REQUEST_INVALID라 로그로만
     * 가린다. 목록 밖의 값은 other로 남는다.
     */
    private static final Set<String> REJECTIONS =
            Set.of(
                    "INVALID_INPUT",
                    "NOT_FOUND",
                    "ALREADY_CONSUMED",
                    "STATE_MISMATCH",
                    "SETTINGS_MISMATCH",
                    "EXPIRED",
                    "CREATED_IN_FUTURE",
                    "CORRUPT_STATE");

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
        // 요청 ID·state·nonce·code_verifier·인증 URL은 로그인 과정의 비밀 값이라 남기지 않는다
        AppLogger.current().info("login request create");
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
        // 요청 ID·state·code는 로그인 과정의 비밀 값이라 남기지 않는다
        AppLogger.current().info("login request consume");
        validateCallbackInput(callback);
        var notConsumed = AuthFailure.Consumption.NOT_CONSUMED;
        OidcClient.Settings settings;
        OAuthStateStore.State storedState;
        try {
            settings = oidcClient.settings();
            storedState =
                    stateStore
                            .find(callback.loginRequestId())
                            .orElseThrow(
                                    () -> {
                                        // 만료돼 Redis에서 사라졌거나 처음부터 없는 요청이다
                                        AppLogger.current()
                                                .warn(
                                                        "login request rejected",
                                                        LogField.errorType(
                                                                AuthFailure.Reason
                                                                        .OAUTH_REQUEST_INVALID
                                                                        .name()),
                                                        LogField.resultCode(
                                                                "reason", "NOT_FOUND", REJECTIONS),
                                                        LogField.result(
                                                                "login_request_consumed", false));
                                        return invalid(notConsumed);
                                    });
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
            // 같은 ID의 요청이 이미 있어 새 ID로 다시 만든다. ID 값은 남기지 않는다
            AppLogger.current()
                    .warn(
                            "login request id collision",
                            LogField.errorType("LOGIN_REQUEST_ID_COLLISION"),
                            LogField.param("attempt", attempt));
        }
        throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
    }

    private void validateCallbackInput(Callback callback) {
        if (callback == null
                || !OAuthSecrets.valid(callback.loginRequestId())
                || !OAuthSecrets.valid(callback.state())
                || (isBlank(callback.code()) == isBlank(callback.error()))) {
            // 콜백 값의 형식이 틀렸거나 code·error가 둘 다 있거나 둘 다 없다. 저장소를 보기 전이라 소모하지 않았다
            AppLogger.current()
                    .warn(
                            "login request rejected",
                            LogField.errorType(AuthFailure.Reason.OAUTH_REQUEST_INVALID.name()),
                            LogField.resultCode("reason", "INVALID_INPUT", REJECTIONS),
                            LogField.result("login_request_consumed", false));
            throw invalid(AuthFailure.Consumption.NOT_CONSUMED);
        }
    }

    private OAuthStateStore.State consumeStoredState(String loginRequestId) {
        try {
            return stateStore
                    .consume(loginRequestId)
                    .orElseThrow(
                            () -> {
                                // 조회와 소모 사이에 다른 콜백이 먼저 가져갔다(동시 콜백). 이 콜백은 소모하지 못했다
                                AppLogger.current()
                                        .warn(
                                                "login request rejected",
                                                LogField.errorType(
                                                        AuthFailure.Reason.OAUTH_REQUEST_INVALID
                                                                .name()),
                                                LogField.resultCode(
                                                        "reason", "ALREADY_CONSUMED", REJECTIONS),
                                                LogField.result("login_request_consumed", false));
                                return invalid(AuthFailure.Consumption.NOT_CONSUMED);
                            });
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
        // 거절 이유. 예전의 한 줄 조건과 같은 순서로 검사해, 먼저 걸린 조건 하나를 로그에 남긴다
        String rejected = null;
        if (storedState.schemaVersion() != STATE_SCHEMA_VERSION) rejected = "CORRUPT_STATE";
        else if (!callbackState.equals(storedState.state())) rejected = "STATE_MISMATCH";
        else if (!settings.registrationId().equals(storedState.registrationId())
                || !settings.clientId().equals(storedState.clientId())
                || !settings.redirectUri().equals(storedState.redirectUri()))
            rejected = "SETTINGS_MISMATCH";
        else if (!clock.instant().isBefore(storedState.expiresAt())) rejected = "EXPIRED";
        else if (storedState.createdAt().isAfter(clock.instant())) rejected = "CREATED_IN_FUTURE";
        if (rejected != null) {
            AppLogger.current()
                    .warn(
                            "login request rejected",
                            LogField.errorType(AuthFailure.Reason.OAUTH_REQUEST_INVALID.name()),
                            LogField.resultCode("reason", rejected, REJECTIONS),
                            LogField.result("login_request_consumed", consumed(consumption)));
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
        if (failure.kind() == PortFailure.Kind.INVALID_DATA) {
            // 저장된 값을 읽지 못했다. 원인 줄(oauth state decode failed)은 저장소가 남긴다
            AppLogger.current()
                    .warn(
                            "login request rejected",
                            LogField.errorType(AuthFailure.Reason.OAUTH_REQUEST_INVALID.name()),
                            LogField.resultCode("reason", "CORRUPT_STATE", REJECTIONS),
                            LogField.result("login_request_consumed", consumed(consumption)));
        }
        return new AuthFailure(reason, consumption);
    }

    /** 로그에 남길 소모 여부. 알 수 없으면(UNKNOWN) null이라 필드가 빠진다. */
    private static Boolean consumed(AuthFailure.Consumption consumption) {
        return switch (consumption) {
            case CONSUMED -> Boolean.TRUE;
            case NOT_CONSUMED -> Boolean.FALSE;
            case UNKNOWN -> null;
        };
    }
}
