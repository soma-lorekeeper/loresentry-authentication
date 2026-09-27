package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.application.port.in.AuthFailure;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;

public final class ErrorResponses {
    public record Contract(int status, String message, String nextAction) {}

    private ErrorResponses() {}

    public static Contract contract(AuthFailure.Reason reason) {
        return switch (reason) {
            case INVALID_SESSION_ID -> new Contract(400, "Invalid session ID.", "NONE");
            case INVALID_REQUEST -> new Contract(400, "Invalid request.", "NONE");
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
        if (callback(request)) {
            Boolean consumed =
                    auth == null
                                    || auth.consumption() == null
                                    || auth.consumption() == AuthFailure.Consumption.UNKNOWN
                            ? null
                            : auth.consumption() == AuthFailure.Consumption.CONSUMED;
            body.put("login_request_consumed", consumed);
        }
        if (reason == AuthFailure.Reason.INTERNAL_ERROR) logSafe(failure);
        return ResponseEntity.status(contract.status())
                .header("Cache-Control", "no-store")
                .body(body);
    }

    public static boolean callback(HttpServletRequest request) {
        return (request.getContextPath() + "/auth/oauth/google/callback")
                .equals(request.getRequestURI());
    }

    private static void logSafe(Throwable failure) {
        StringBuilder diagnostic = new StringBuilder();
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure;
                cause != null && visited.size() < 8 && visited.add(cause);
                cause = cause.getCause()) {
            diagnostic.append(cause.getClass().getName()).append('\n');
            Arrays.stream(cause.getStackTrace())
                    .limit(30)
                    .forEach(frame -> diagnostic.append("  at ").append(frame).append('\n'));
        }
        // Exception messages, request data, SQL and provider bodies are intentionally never
        // rendered.
        LoggerFactory.getLogger(ErrorResponses.class)
                .error("Unhandled authentication error:\n{}", diagnostic);
    }
}
