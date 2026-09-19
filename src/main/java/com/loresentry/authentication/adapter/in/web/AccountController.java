package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.adapter.in.web.dto.AuthRequests;
import com.loresentry.authentication.adapter.in.web.dto.AuthResponses;
import com.loresentry.authentication.adapter.in.web.mapper.AuthResponseMapper;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/auth/users/me")
@RequiredArgsConstructor
public class AccountController {
    private final AccountUseCase accounts;
    private final AuthResponseMapper responses;
    @GetMapping public ResponseEntity<AuthResponses.Profile> get(HttpServletRequest request) { return profile(accounts.get(userId(request))); }
    @PatchMapping public ResponseEntity<AuthResponses.Profile> rename(HttpServletRequest request, @Valid @RequestBody AuthRequests.Rename body) {
        UUID id = userId(request);
        return profile(accounts.rename(id, body.displayName()));
    }
    private UUID userId(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("X-User-Id"));
        if (values.isEmpty()) throw new AuthFailure(AuthFailure.Reason.USER_CONTEXT_REQUIRED);
        if (values.size() != 1) throw invalidRequest();
        try {
            var text = values.getFirst(); UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) throw invalidRequest();
            return id;
        } catch (IllegalArgumentException invalid) { throw invalidRequest(); }
    }
    private AuthFailure invalidRequest() { return new AuthFailure(AuthFailure.Reason.INVALID_REQUEST); }
    private ResponseEntity<AuthResponses.Profile> profile(AccountUseCase.Profile profile) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(responses.profile(profile));
    }
}
