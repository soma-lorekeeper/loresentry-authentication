package com.loresentry.authentication.application.service;

import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import lombok.RequiredArgsConstructor;

/**
 * RT 검증, 기존 RT 소모, 새 토큰 발급·저장을 순서대로 수행한다.
 *
 * <p>소모 명령은 재시도하지 않으며 미실행, 결과 불명, 새 RT 저장 실패를 구분하여 보고한다.
 */
@RequiredArgsConstructor
public final class RefreshService implements RefreshUseCase {
    private final JwtTokens jwt;
    private final RefreshTokenStore store;

    public TokenPair refresh(String token) {
        JwtTokens.RefreshClaims claims;
        try {
            claims = jwt.verifyRefresh(token, false);
        } catch (PortFailure e) {
            throw new AuthFailure(REFRESH_REJECTED);
        }
        try {
            var owner =
                    store.consume(claims.jti())
                            .orElseThrow(() -> new AuthFailure(REFRESH_REJECTED));
            if (!owner.equals(claims.userId())) throw new AuthFailure(REFRESH_REJECTED);
        } catch (PortFailure e) {
            if (e.kind() == PortFailure.Kind.INVALID_DATA) throw new AuthFailure(REFRESH_REJECTED);
            throw new AuthFailure(
                    e.execution() == PortFailure.Execution.NOT_EXECUTED
                            ? REFRESH_UNAVAILABLE
                            : REFRESH_OUTCOME_UNKNOWN);
        }
        try {
            var issued = jwt.issue(claims.userId());
            store.save(issued.refreshJti(), claims.userId(), issued.tokens().refreshExpiresAt());
            return issued.tokens();
        } catch (PortFailure e) {
            throw new AuthFailure(REFRESH_SAVE_FAILED);
        }
    }
}
