package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.adapter.in.web.dto.AuthRequests;
import com.loresentry.authentication.adapter.in.web.dto.AuthResponses;
import com.loresentry.authentication.adapter.in.web.mapper.AuthRequestMapper;
import com.loresentry.authentication.adapter.in.web.mapper.AuthResponseMapper;
import com.loresentry.authentication.application.port.in.PasswordAuthUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth/password")
@RequiredArgsConstructor
public class PasswordController {
    private final PasswordAuthUseCase passwords;
    private final AuthRequestMapper requests;
    private final AuthResponseMapper responses;

    @PostMapping("/signup")
    public ResponseEntity<AuthResponses.PasswordLogin> signUp(
            @Valid @RequestBody AuthRequests.SignUp request) {
        return ok(responses.passwordLogin(passwords.signUp(requests.signUp(request))));
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponses.PasswordLogin> login(
            @Valid @RequestBody AuthRequests.PasswordLogin request) {
        return ok(responses.passwordLogin(passwords.login(requests.passwordLogin(request))));
    }

    private static <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
