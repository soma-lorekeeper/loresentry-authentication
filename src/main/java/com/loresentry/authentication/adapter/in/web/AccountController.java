package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.application.port.in.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;
import java.util.*;

@RestController
@RequestMapping("/auth/users/me")
public class AccountController {
    private final AccountUseCase accounts;
    public AccountController(AccountUseCase accounts) { this.accounts = accounts; }
    @GetMapping public ResponseEntity<Map<String, Object>> get(HttpServletRequest request) { return profile(accounts.get(userId(request))); }
    @PatchMapping public ResponseEntity<Map<String, Object>> rename(HttpServletRequest request, @RequestBody JsonNode body) {
        UUID id = userId(request);
        JsonInputs.object(body, Set.of("display_name"), false);
        String name = JsonInputs.optional(body, "display_name", false);
        if (name == null) throw JsonInputs.invalid(false);
        return profile(accounts.rename(id, name));
    }
    private UUID userId(HttpServletRequest request) {
        var values = Collections.list(request.getHeaders("X-User-Id"));
        if (values.isEmpty()) throw new AuthFailure(AuthFailure.Reason.USER_CONTEXT_REQUIRED);
        if (values.size() != 1) throw JsonInputs.invalid(false);
        try {
            var text = values.getFirst(); UUID id = UUID.fromString(text);
            if (!id.toString().equalsIgnoreCase(text)) throw JsonInputs.invalid(false);
            return id;
        } catch (IllegalArgumentException invalid) { throw JsonInputs.invalid(false); }
    }
    private ResponseEntity<Map<String, Object>> profile(AccountUseCase.Profile profile) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", profile.id()); body.put("display_name", profile.displayName()); body.put("email", profile.email());
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
