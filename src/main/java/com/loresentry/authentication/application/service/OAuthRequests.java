package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.LoginUseCase.PreparedLogin;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.OAuthSecrets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.temporal.ChronoUnit;

public final class OAuthRequests {
    private final OAuthStateStore store;
    private final OidcClient provider;
    private final Clock clock;
    private final SecureRandom random;
    public OAuthRequests(OAuthStateStore store, OidcClient provider, Clock clock, SecureRandom random) {
        this.store = store; this.provider = provider; this.clock = clock; this.random = random;
    }
    public PreparedLogin prepare() {
        try {
            var settings = provider.settings();
            var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
            var state = new OAuthStateStore.State(1, settings.registrationId(), settings.clientId(), settings.redirectUri(),
                OAuthSecrets.generate(random), OAuthSecrets.generate(random), OAuthSecrets.generate(random), now, now.plusSeconds(300));
            // Collisions cannot overwrite another request; cap attempts if the entropy source is broken.
            for (int attempt = 0; attempt < 5; attempt++) {
                var id = OAuthSecrets.generate(random);
                if (store.create(id, state)) return new PreparedLogin(provider.authorizationUrl(state), id, state.expiresAt());
            }
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE);
        } catch (PortFailure e) { throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE); }
    }
}
