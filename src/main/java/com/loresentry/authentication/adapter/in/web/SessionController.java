package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.adapter.in.web.dto.AuthRequests;
import com.loresentry.authentication.application.port.in.RevokeSessionUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class SessionController {
    private final RevokeSessionUseCase revoke;

    @PostMapping("/auth/sessions/revoke")
    public ResponseEntity<Void> revoke(@RequestBody AuthRequests.Session request) {
        revoke.revoke(request.sessionId());
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }
}
