package com.loresentry.authentication.application.service;

import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import lombok.RequiredArgsConstructor;

/** 새 토큰을 먼저 서명하고 현재 세션·RT가 일치할 때만 원자적으로 교체한다. 갱신은 재시도하지 않는다. */
@RequiredArgsConstructor
public final class RefreshService implements RefreshUseCase {
    private final JwtTokens jwt;
    private final SessionStore store;

    public TokenPair refresh(String token) {
        JwtTokens.RefreshClaims claims;
        try {
            claims = jwt.verifyRefresh(token, false);
        } catch (PortFailure e) {
            throw new AuthFailure(REFRESH_REJECTED);
        }
        JwtTokens.Issued issued;
        try {
            issued = jwt.issue(claims.userId(), claims.sid());
        } catch (RuntimeException e) {
            throw new AuthFailure(INTERNAL_ERROR);
        }
        try {
            var expected = new SessionStore.Session(claims.sid(), claims.jti(), claims.expiresAt());
            var replacement =
                    new SessionStore.Session(
                            claims.sid(), issued.refreshJti(), issued.tokens().refreshExpiresAt());
            if (!store.rotate(claims.userId(), expected, replacement)) {
                throw new AuthFailure(REFRESH_REJECTED);
            }
            return issued.tokens();
        } catch (PortFailure e) {
            if (e.kind() == PortFailure.Kind.INVALID_DATA) throw new AuthFailure(INTERNAL_ERROR);
            throw new AuthFailure(
                    e.execution() == PortFailure.Execution.NOT_EXECUTED
                            ? REFRESH_UNAVAILABLE
                            : REFRESH_OUTCOME_UNKNOWN);
        }
    }
}
