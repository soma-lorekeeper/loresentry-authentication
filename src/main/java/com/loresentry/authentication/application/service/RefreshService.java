package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

public final class RefreshService implements RefreshUseCase {
    private final JwtTokens jwt;
    private final RefreshTokenStore store;
    public RefreshService(JwtTokens jwt, RefreshTokenStore store) { this.jwt = jwt; this.store = store; }
    public TokenPair refresh(String token) {
        JwtTokens.RefreshClaims claims;
        try { claims = jwt.verifyRefresh(token, false); }
        catch (PortFailure e) { throw new AuthFailure(REFRESH_REJECTED); }
        try {
            var owner = store.consume(claims.jti()).orElseThrow(() -> new AuthFailure(REFRESH_REJECTED));
            if (!owner.equals(claims.userId())) throw new AuthFailure(REFRESH_REJECTED);
        } catch (PortFailure e) {
            if (e.kind() == PortFailure.Kind.INVALID_DATA) throw new AuthFailure(REFRESH_REJECTED);
            throw new AuthFailure(e.execution() == PortFailure.Execution.NOT_EXECUTED
                ? REFRESH_UNAVAILABLE : REFRESH_OUTCOME_UNKNOWN);
        }
        try {
            var issued = jwt.issue(claims.userId());
            store.save(issued.refreshJti(), claims.userId(), issued.tokens().refreshExpiresAt());
            return issued.tokens();
        } catch (PortFailure e) { throw new AuthFailure(REFRESH_SAVE_FAILED); }
    }
}
