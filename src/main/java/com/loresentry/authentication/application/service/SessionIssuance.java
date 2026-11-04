package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.SessionId;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.time.Instant;
import java.util.UUID;

final class SessionIssuance {
    private SessionIssuance() {}

    record Issued(SessionId id, Instant expiresAt) {}

    static Issued create(
            UUID user, SessionIdGenerator ids, LoginSessionStore sessions, AccountStore accounts) {
        try {
            for (int attempt = 0; attempt < 3; attempt++) {
                var id = ids.generate();
                var expiry = sessions.replace(user, id);
                if (expiry.isEmpty()) {
                    // 같은 ID의 세션이 이미 있어 새 ID로 다시 만든다. 세션 ID는 인증 수단이라 남기지 않는다
                    AppLogger.current()
                            .warn(
                                    "session id collision",
                                    LogField.errorType("SESSION_ID_COLLISION"),
                                    LogField.param("attempt", attempt));
                    continue;
                }
                if (accounts.findById(user).isEmpty()) {
                    // 세션을 만든 직후 계정이 사라졌다(동시 탈퇴). 만든 세션을 지우고 로그인을 멈춘다. 사용자는 부른 쪽(로그인, 약관 동의)이
                    // 이미 알려 두었다
                    AppLogger.current()
                            .warn(
                                    "login session issue aborted: account missing",
                                    LogField.errorType(
                                            AuthFailure.Reason.LOGIN_UNAVAILABLE.name()));
                    try {
                        sessions.revoke(id);
                    } catch (PortFailure ignored) {
                        // 응답은 그대로 503이다. 지우지 못한 세션은 TTL까지 남을 수 있어 warn으로 남긴다
                        AppLogger.current()
                                .warn(
                                        "login session cleanup failed",
                                        LogField.errorType(ignored.kind().name()));
                    }
                    throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
                }
                return new Issued(id, expiry.get());
            }
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        } catch (PortFailure error) {
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        }
    }
}
