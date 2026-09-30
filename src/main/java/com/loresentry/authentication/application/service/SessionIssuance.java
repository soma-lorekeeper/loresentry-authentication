package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.SessionId;
import java.time.Instant;
import java.util.UUID;

final class SessionIssuance {
    private SessionIssuance() {}

    record Issued(SessionId id, Instant expiresAt) {}

    static Issued create(UUID user, SessionIdGenerator ids, LoginSessionStore sessions) {
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                var id = ids.generate();
                var expiry = sessions.replace(user, id);
                if (expiry.isPresent()) return new Issued(id, expiry.get());
            }
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        } catch (PortFailure error) {
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        }
    }
}
