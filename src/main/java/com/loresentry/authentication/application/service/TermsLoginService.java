package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.TermsLoginGate;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.ConsentId;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class TermsLoginService implements TermsLoginGate {
    private final boolean enabled;
    private final TermsVersionStore versions;
    private final TermsAcceptanceStore acceptances;
    private final ConsentRequestStore requests;
    private final SessionIdGenerator ids;
    private final Clock clock;

    public Optional<Required> check(UUID userId) {
        // enabled가 false면 약관 확인 없이 바로 로그인한다
        AppLogger.current().info("terms login check", LogField.param("enabled", enabled));
        if (!enabled) return Optional.empty();
        try {
            var version =
                    versions.current(clock.instant())
                            .orElseThrow(
                                    () -> {
                                        AppLogger.current()
                                                .warn(
                                                        "terms current version missing",
                                                        LogField.errorType(
                                                                AuthFailure.Reason.LOGIN_UNAVAILABLE
                                                                        .name()));
                                        return new AuthFailure(
                                                AuthFailure.Reason.LOGIN_UNAVAILABLE);
                                    });
            if (acceptances.hasAccepted(userId, version.id())) return Optional.empty();
            for (int attempt = 0; attempt < 3; attempt++) {
                var id = new ConsentId(ids.generate().value());
                var pending = requests.create(id, userId, version.id());
                if (pending.isPresent())
                    return Optional.of(new Required(id, pending.get().expiresAt()));
                // 같은 ID의 동의 요청이 이미 있어 새 ID로 다시 만든다. ID 값은 남기지 않는다
                AppLogger.current()
                        .warn(
                                "consent request id collision",
                                LogField.errorType("CONSENT_REQUEST_ID_COLLISION"),
                                LogField.param("attempt", attempt));
            }
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        } catch (PortFailure error) {
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        }
    }
}
