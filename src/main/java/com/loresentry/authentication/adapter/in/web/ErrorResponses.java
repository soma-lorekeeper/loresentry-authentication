package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.ResponseEntity;

public final class ErrorResponses {
    public record Contract(int status, String message, String nextAction) {}

    private ErrorResponses() {}

    public static Contract contract(AuthFailure.Reason reason) {
        return switch (reason) {
            case INVALID_SESSION_ID -> new Contract(400, "Invalid session ID.", "NONE");
            case INVALID_REQUEST -> new Contract(400, "Invalid request.", "NONE");
            case CONSENT_REQUEST_INVALID ->
                    new Contract(401, "Consent request is invalid or expired.", "RESTART_LOGIN");
            case TERMS_VERSION_MISMATCH -> new Contract(409, "Terms version has changed.", "NONE");
            case INVALID_DISPLAY_NAME -> new Contract(400, "Invalid display name.", "NONE");
            case OAUTH_REQUEST_INVALID ->
                    new Contract(400, "Login request is invalid or expired.", "RESTART_LOGIN");
            case OAUTH_LOGIN_DENIED -> new Contract(400, "Login was denied.", "RESTART_LOGIN");
            case OAUTH_IDENTITY_INVALID ->
                    new Contract(401, "Identity could not be verified.", "RESTART_LOGIN");
            case USER_CONTEXT_REQUIRED -> new Contract(401, "User context is required.", "RELOGIN");
            case USER_NOT_FOUND -> new Contract(404, "User was not found.", "RELOGIN");
            case LOGIN_UNAVAILABLE ->
                    new Contract(503, "Login is temporarily unavailable.", "RESTART_LOGIN");
            case REVOCATION_UNCONFIRMED ->
                    new Contract(503, "Revocation could not be confirmed.", "NONE");
            case ACCOUNT_UNAVAILABLE ->
                    new Contract(503, "Account service is temporarily unavailable.", "RETRY_LATER");
            // 없는 경로. 내부 오류(500)와 섞이지 않게 따로 답한다. content 의 NOT_FOUND 와 같다
            case NOT_FOUND -> new Contract(404, "No such endpoint.", "NONE");
            case INTERNAL_ERROR -> new Contract(500, "An internal error occurred.", "NONE");
        };
    }

    public static ResponseEntity<Map<String, Object>> response(
            Throwable failure, HttpServletRequest request) {
        AuthFailure auth = failure instanceof AuthFailure value ? value : null;
        var reason = auth == null ? AuthFailure.Reason.INTERNAL_ERROR : auth.reason();
        var contract = contract(reason);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", reason.name());
        body.put("message", contract.message());
        body.put("next_action", contract.nextAction());
        // 문의 받은 값을 Kibana 에서 바로 찾도록 trace ID 를 싣는다(LOG_SCHEMA.md 7절 응답). gateway 의 trace 와 같은 값이다
        String traceId = AppLogger.current().traceId();
        if (traceId != null) body.put("trace_id", traceId);
        if (callback(request)) {
            Boolean consumed =
                    auth == null
                                    || auth.consumption() == null
                                    || auth.consumption() == AuthFailure.Consumption.UNKNOWN
                            ? null
                            : auth.consumption() == AuthFailure.Consumption.CONSUMED;
            body.put("login_request_consumed", consumed);
            // 콜백 실패가 로그인 요청을 소모했는지 결과 줄에도 남긴다. 알 수 없으면(null) 남기지 않는다
            if (consumed != null)
                AppLogger.current()
                        .addToOutcome(LogField.result("login_request_consumed", consumed));
        }
        // 실패는 따로 찍지 않고 이유(우리 오류 코드)를 결과 줄(request completed)에 맡긴다. 그래서 실패 요청 하나의 결과는 한 줄이다
        AppLogger.current().addToOutcome(LogField.errorType(reason.name()));
        if (reason == AuthFailure.Reason.INTERNAL_ERROR) {
            // 원인은 그 자리에서 warn 으로 남긴다. 예외 메시지·요청 값·SQL·provider 본문은 의도적으로 남기지 않는다.
            // 원인마다 클래스 이름과 프레임만 남긴다.
            AppLogger.current()
                    .warn(
                            "request failed with an internal error",
                            LogField.errorType(failure.getClass().getName()),
                            LogField.safeStackTrace(failure));
        }
        return ResponseEntity.status(contract.status())
                .header("Cache-Control", "no-store")
                .body(body);
    }

    public static boolean callback(HttpServletRequest request) {
        return (request.getContextPath() + "/auth/oauth/google/callback")
                .equals(request.getRequestURI());
    }
}
