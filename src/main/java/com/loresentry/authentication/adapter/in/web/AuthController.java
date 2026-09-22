package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.adapter.in.web.dto.AuthRequests;
import com.loresentry.authentication.adapter.in.web.dto.AuthResponses;
import com.loresentry.authentication.adapter.in.web.mapper.AuthResponseMapper;
import com.loresentry.authentication.adapter.in.web.mapper.AuthRequestMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final LoginUseCase login;
    private final RefreshUseCase refresh;
    private final RevokeUseCase revoke;
    private final AuthResponseMapper responses;
    private final AuthRequestMapper requests;

    @PostMapping("/oauth/google/prepare")
    public ResponseEntity<AuthResponses.PreparedLogin> prepare(@Valid @RequestBody AuthRequests.Prepare request) {
        var prepared = login.prepare();
        return ok(responses.preparedLogin(prepared));
    }
    @PostMapping("/oauth/google/callback")
    public ResponseEntity<AuthResponses.Callback> callback(@Valid @RequestBody AuthRequests.Callback request) {
        var result = login.callback(requests.callback(request));
        return ok(responses.callback(result));
    }
    @PostMapping("/tokens/refresh")
    public ResponseEntity<AuthResponses.Tokens> refresh(@Valid @RequestBody AuthRequests.RefreshToken request) {
        return ok(responses.tokens(refresh.refresh(request.refreshToken())));
    }
    @PostMapping("/tokens/revoke")
    public ResponseEntity<Void> revoke(@Valid @RequestBody AuthRequests.RefreshToken request) {
        revoke.revoke(request.refreshToken());
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
    private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(body); }
}
