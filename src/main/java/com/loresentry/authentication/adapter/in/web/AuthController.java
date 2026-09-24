package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.adapter.in.web.dto.AuthRequests;
import com.loresentry.authentication.adapter.in.web.dto.AuthResponses;
import com.loresentry.authentication.adapter.in.web.mapper.AuthRequestMapper;
import com.loresentry.authentication.adapter.in.web.mapper.AuthResponseMapper;
import com.loresentry.authentication.application.port.in.LoginUseCase;
import com.loresentry.authentication.application.port.in.RefreshUseCase;
import com.loresentry.authentication.application.port.in.RevokeUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BFF의 내부 인증 요청을 UseCase에 전달하고 결과를 JSON 응답으로 변환한다.
 *
 * <p>브라우저 리다이렉트와 쿠키 설정은 BFF가 담당한다. 성공 응답에는 no-store를 지정한다.
 */
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {
    private final LoginUseCase login;
    private final RefreshUseCase refresh;
    private final RevokeUseCase revoke;
    private final AuthResponseMapper responseMapper;
    private final AuthRequestMapper requestMapper;

    /**
     * 로그인 준비 결과를 반환한다.
     *
     * @param request 필드가 없는 요청 DTO. 현재 HTTP 계약은 빈 JSON 객체를 받음
     * @return 인증 URL, 요청 식별자와 만료 시각을 담은 응답
     */
    @PostMapping("/oauth/google/prepare")
    public ResponseEntity<AuthResponses.PreparedLogin> prepare(
            @Valid @RequestBody AuthRequests.Prepare request) {
        var preparedLogin = login.prepare();
        var response = responseMapper.preparedLogin(preparedLogin);
        return ok(response);
    }

    @PostMapping("/oauth/google/callback")
    public ResponseEntity<AuthResponses.Callback> callback(
            @Valid @RequestBody AuthRequests.Callback request) {
        var command = requestMapper.callback(request);
        var loginResult = login.callback(command);
        var response = responseMapper.callback(loginResult);
        return ok(response);
    }

    @PostMapping("/tokens/refresh")
    public ResponseEntity<AuthResponses.Tokens> refresh(
            @Valid @RequestBody AuthRequests.RefreshToken request) {
        var tokenPair = refresh.refresh(request.refreshToken());
        var response = responseMapper.tokens(tokenPair);
        return ok(response);
    }

    @PostMapping("/tokens/revoke")
    public ResponseEntity<Void> revoke(@Valid @RequestBody AuthRequests.RefreshToken request) {
        revoke.revoke(request.refreshToken());
        return ResponseEntity.noContent().header("Cache-Control", "no-store").build();
    }

    private <T> ResponseEntity<T> ok(T body) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(body);
    }
}
