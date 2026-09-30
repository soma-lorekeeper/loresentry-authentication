package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.TermsLoginGate;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.ConsentId;
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
        if (!enabled) return Optional.empty();
        try {
            var version =
                    versions.current(clock.instant())
                            .orElseThrow(
                                    () -> new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE));
            if (acceptances.hasAccepted(userId, version.id())) return Optional.empty();
            for (int attempt = 0; attempt < 3; attempt++) {
                var id = new ConsentId(ids.generate().value());
                var pending = requests.create(id, userId, version.id());
                if (pending.isPresent())
                    return Optional.of(new Required(id, pending.get().expiresAt()));
            }
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        } catch (PortFailure error) {
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        }
    }
}
