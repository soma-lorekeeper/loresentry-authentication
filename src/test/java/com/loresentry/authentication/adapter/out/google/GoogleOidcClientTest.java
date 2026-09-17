package com.loresentry.authentication.adapter.out.google;

import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.OAuthSecrets;
import com.loresentry.authentication.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.loresentry.authentication.config.*;
import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class GoogleOidcClientTest {
    final GoogleSettings settings = GoogleSettings.validated("test-client", "test-only-secret", "https://api.loresentry.com/auth/callback/google", false);
    OAuthStateStore.State state() {
        var random = new SecureRandom(); var now = Instant.now();
        return new OAuthStateStore.State(1, "google", settings.clientId(), settings.redirectUri(), OAuthSecrets.generate(random),
            OAuthSecrets.generate(random), OAuthSecrets.generate(random), now, now.plusSeconds(300));
    }
    @Test void codeExchangeRestoresPkceAndNonceAndReturnsVerifiedIdentity() {
        try (var google = new MockGoogle(); var client = new GoogleOidcClient(settings, Clock.systemUTC(), google.endpoints())) {
            var state = state(); var parameters = MockGoogle.form(client.authorizationUrl(state).getRawQuery());
            assertThat(parameters).containsEntry("client_id", "test-client").containsEntry("redirect_uri", settings.redirectUri())
                .containsEntry("state", state.state()).containsEntry("response_type", "code").containsEntry("response_mode", "query")
                .containsEntry("code_challenge_method", "S256").containsEntry("code_challenge", OAuthSecrets.hash(state.codeVerifier()))
                .containsEntry("nonce", OAuthSecrets.hash(state.nonce()));
            assertThat(Set.of(parameters.get("scope").split(" "))).containsExactlyInAnyOrder("openid", "email", "profile");
            assertThat(parameters).doesNotContainKeys("access_type", "code_verifier", "client_secret");
            google.tokens.put("code", google.sign(google.claims(parameters.get("nonce"), "google-user").build()));
            assertThat(client.exchange("code", state)).isEqualTo(new OidcClient.Identity("google", "google-user", "Test User", "test@example.com"));
            assertThat(google.lastForm).containsEntry("code_verifier", state.codeVerifier()).containsEntry("grant_type", "authorization_code")
                .containsEntry("redirect_uri", settings.redirectUri()).doesNotContainKey("client_secret");
            assertThat(google.lastAuthorization).isEqualTo("Basic " + Base64.getEncoder().encodeToString("test-client:test-only-secret".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertThat(google.tokenCalls.get()).isEqualTo(1); assertThat(google.unexpectedCalls.get()).isZero();
        }
    }
    @Test void rejectsNonceIssuerAudienceExpiryMissingClaimsSignatureAndUntrustedKeyUrls() {
        try (var google = new MockGoogle(); var client = new GoogleOidcClient(settings, Clock.systemUTC(), google.endpoints())) {
            var state = state(); var nonce = OAuthSecrets.hash(state.nonce());
            var claims = List.of(google.claims("wrong", "sub").build(), google.claims(nonce, "sub").issuer("https://evil.example").build(),
                google.claims(nonce, "sub").audience("loresentry-api").build(),
                google.claims(nonce, "sub").issueTime(Date.from(Instant.now().minusSeconds(600))).expirationTime(Date.from(Instant.now().minusSeconds(120))).build(),
                google.claims(nonce, "sub").claim("nonce", null).build(), google.claims(nonce, "sub").expirationTime(null).build(),
                google.claims(nonce, "sub").issueTime(null).build(), google.claims(nonce, "sub").subject(null).build());
            for (var value : claims) { google.tokens.put("code", google.sign(value)); invalid(() -> client.exchange("code", state)); }
            var impostor = TestKeys.generate(2048);
            google.tokens.put("code", google.sign(google.claims(nonce, "sub").build(), impostor, google.kid, null));
            invalid(() -> client.exchange("code", state));
            google.tokens.put("code", google.sign(google.claims(nonce, "sub").build(), impostor, "unregistered", URI.create(google.base()+"/attacker-keys")));
            invalid(() -> client.exchange("code", state));
            assertThat(google.unexpectedCalls.get()).isZero();
        }
    }
    @Test void timeoutDoesNotRetryTheAuthorizationCode() {
        try (var google = new MockGoogle(); var client = new GoogleOidcClient(settings, Clock.systemUTC(), google.endpoints())) {
            var state = state(); google.tokenDelayMillis = 4000;
            google.tokens.put("code", google.sign(google.claims(OAuthSecrets.hash(state.nonce()), "sub").build()));
            assertThatThrownBy(() -> client.exchange("code", state)).isInstanceOfSatisfying(PortFailure.class,
                e -> { assertThat(e.kind()).isEqualTo(PortFailure.Kind.UNAVAILABLE); assertThat(e).hasNoCause(); });
            assertThat(google.tokenCalls.get()).isEqualTo(1);
        }
    }
    @Test void invalidConfigurationFailsStartupAndHttpRequiresLocalLoopback() {
        for (var uri : List.of("https://evil.example/callback", "http://api.loresentry.com/auth/callback/google", "http://localhost/callback", "bad"))
            assertThatThrownBy(() -> GoogleSettings.validated("id", "secret", uri, false)).hasMessage("Invalid Google OAuth configuration");
        assertThat(GoogleSettings.validated("id", "secret", "http://localhost:3000/auth/callback/google", true)).isNotNull();
        assertThatThrownBy(() -> GoogleSettings.validated("id", "secret", "http://evil.example/callback", true)).isInstanceOf(IllegalStateException.class);
        new ApplicationContextRunner().withUserConfiguration(GoogleConfiguration.class, CoreConfiguration.class)
            .run(context -> assertThat(context).hasFailed());
        new ApplicationContextRunner().withUserConfiguration(GoogleConfiguration.class, CoreConfiguration.class)
            .withPropertyValues("auth.google.client-id=id", "auth.google.client-secret=test-only", "auth.google.redirect-uri="+settings.redirectUri())
            .run(context -> assertThat(context).hasNotFailed().hasSingleBean(GoogleOidcClient.class));
    }
    void invalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable task) {
        assertThatThrownBy(task).isInstanceOfSatisfying(PortFailure.class, e -> assertThat(e.kind()).isEqualTo(PortFailure.Kind.INVALID_IDENTITY));
    }
}
