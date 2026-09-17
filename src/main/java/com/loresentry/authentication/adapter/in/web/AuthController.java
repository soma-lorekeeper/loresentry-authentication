package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.application.port.in.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
    private final LoginUseCase login;
    private final RefreshUseCase refresh;
    private final RevokeUseCase revoke;
    public AuthController(LoginUseCase login, RefreshUseCase refresh, RevokeUseCase revoke) { this.login = login; this.refresh = refresh; this.revoke = revoke; }
    @PostMapping("/oauth/google/prepare")
    public ResponseEntity<Map<String, Object>> prepare(@RequestBody JsonNode body) {
        JsonInputs.object(body, Set.of(), false);
        var prepared = login.prepare();
        return ok(Map.of("authorization_url", prepared.authorizationUrl(), "login_request_id", prepared.loginRequestId(), "expires_at", prepared.expiresAt()));
    }
    @PostMapping("/oauth/google/callback")
    public ResponseEntity<Map<String, Object>> callback(@RequestBody JsonNode body) {
        JsonInputs.object(body, Set.of("login_request_id", "state", "code", "error"), true);
        var id = JsonInputs.required(body, "login_request_id", true); var state = JsonInputs.required(body, "state", true);
        var code = JsonInputs.optional(body, "code", true); var error = JsonInputs.optional(body, "error", true);
        if ((code == null || code.isBlank()) == (error == null || error.isBlank()) || (code != null && error != null)) throw JsonInputs.invalid(true);
        var result = login.callback(new LoginUseCase.Callback(id, state, code, error));
        var response = tokens(result.tokens());
        Boolean consumed = switch (result.consumption()) {
            case CONSUMED -> true; case NOT_CONSUMED -> false; case UNKNOWN -> null;
        };
        response.put("login_request_consumed", consumed);
        return ok(response);
    }
    @PostMapping("/tokens/refresh")
    public ResponseEntity<Map<String, Object>> refresh(@RequestBody JsonNode body) {
        JsonInputs.object(body, Set.of("refresh_token"), false);
        return ok(tokens(refresh.refresh(JsonInputs.required(body, "refresh_token", false))));
    }
    @PostMapping("/tokens/revoke")
    public ResponseEntity<Void> revoke(@RequestBody JsonNode body) {
        JsonInputs.object(body, Set.of("refresh_token"), false);
        revoke.revoke(JsonInputs.required(body, "refresh_token", false));
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
    private Map<String, Object> tokens(TokenPair pair) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", pair.accessToken()); body.put("access_expires_at", pair.accessExpiresAt());
        body.put("refresh_token", pair.refreshToken()); body.put("refresh_expires_at", pair.refreshExpiresAt());
        return body;
    }
    private ResponseEntity<Map<String, Object>> ok(Map<String, Object> body) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(body); }
}
