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

    public OAuthStateStore.State consume(com.loresentry.authentication.application.port.in.LoginUseCase.Callback request) {
        var notConsumed = AuthFailure.Consumption.NOT_CONSUMED;
        if (request == null || !OAuthSecrets.valid(request.loginRequestId()) || !OAuthSecrets.valid(request.state())
            || (blank(request.code()) == blank(request.error())))
            throw new AuthFailure(AuthFailure.Reason.OAUTH_REQUEST_INVALID, notConsumed);
        OidcClient.Settings settings;
        OAuthStateStore.State before;
        try {
            settings = provider.settings();
            before = store.find(request.loginRequestId()).orElseThrow(() -> invalid(notConsumed));
        } catch (PortFailure failure) { throw storageFailure(failure, notConsumed); }
        validate(before, request.state(), settings, notConsumed);
        OAuthStateStore.State consumed;
        try {
            consumed = store.consume(request.loginRequestId()).orElseThrow(() -> invalid(notConsumed));
        } catch (PortFailure failure) {
            var outcome = switch (failure.execution()) {
                case NOT_EXECUTED -> notConsumed;
                case EXECUTED -> AuthFailure.Consumption.CONSUMED;
                case UNKNOWN -> AuthFailure.Consumption.UNKNOWN;
            };
            throw storageFailure(failure, outcome);
        }
        validate(consumed, request.state(), settings, AuthFailure.Consumption.CONSUMED);
        return consumed;
    }
    private void validate(OAuthStateStore.State value, String state, OidcClient.Settings settings,
                          AuthFailure.Consumption outcome) {
        if (value.schemaVersion() != 1 || !state.equals(value.state())
            || !settings.registrationId().equals(value.registrationId()) || !settings.clientId().equals(value.clientId())
            || !settings.redirectUri().equals(value.redirectUri()) || !clock.instant().isBefore(value.expiresAt())
            || value.createdAt().isAfter(clock.instant())) throw invalid(outcome);
    }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private AuthFailure invalid(AuthFailure.Consumption outcome) {
        return new AuthFailure(AuthFailure.Reason.OAUTH_REQUEST_INVALID, outcome);
    }
    private AuthFailure storageFailure(PortFailure failure, AuthFailure.Consumption outcome) {
        return new AuthFailure(failure.kind() == PortFailure.Kind.INVALID_DATA
            ? AuthFailure.Reason.OAUTH_REQUEST_INVALID : AuthFailure.Reason.LOGIN_UNAVAILABLE, outcome);
    }
}
