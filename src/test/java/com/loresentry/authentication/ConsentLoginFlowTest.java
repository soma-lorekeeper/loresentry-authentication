package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.application.port.out.TermsAcceptanceStore;
import com.loresentry.authentication.support.HttpAuthTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "auth.terms.enabled=true")
@Import(HttpAuthTestSupport.GoogleHttpConfiguration.class)
class ConsentLoginFlowTest extends HttpAuthTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired TermsAcceptanceStore acceptances;
    @Autowired StringRedisTemplate redis;
    final String subject = "consent-" + UUID.randomUUID();
    final List<UUID> originals = new ArrayList<>();

    @AfterEach
    void cleanup() {
        accounts.findByIdentity("google", subject)
                .ifPresent(
                        account -> {
                            var id = account.user().id();
                            jdbc.update("DELETE FROM oauth_identities WHERE user_id = ?", id);
                            jdbc.update("DELETE FROM users WHERE id = ?", id);
                        });
        originals.forEach(id -> jdbc.update("DELETE FROM terms_versions WHERE id = ?", id));
    }

    @Test
    void committedAccountWaitsForCurrentTermsAndFutureTermsDoNotRequireConsent() {
        var current = insert(Instant.now().minusSeconds(60));
        var first = login(subject);
        assertThat(first.body().get("status").asString()).isEqualTo("TERMS_REQUIRED");
        assertThat(first.body().has("session_id")).isFalse();
        var user = accounts.findByIdentity("google", subject).orElseThrow().user().id();
        assertThat(redis.hasKey("auth:session:{login}:by-user:" + user)).isFalse();
        var next = login(subject);
        assertThat(next.body().get("consent_request_id").asString())
                .isNotEqualTo(first.body().get("consent_request_id").asString());
        acceptances.accept(user, current, Instant.now());
        insert(Instant.now().plusSeconds(3600));
        var authenticated = login(subject);
        assertThat(authenticated.body().get("status").asString()).isEqualTo("AUTHENTICATED");
        var active = redis.opsForValue().get("auth:session:{login}:by-user:" + user);
        insert(Instant.now().minusSeconds(30));
        assertThat(login(subject).body().get("status").asString()).isEqualTo("TERMS_REQUIRED");
        assertThat(redis.opsForValue().get("auth:session:{login}:by-user:" + user))
                .isEqualTo(active);
    }

    @Test
    void enabledGateWithoutOriginalFailsAfterAccountCommit() {
        var result = callback(pending(subject));
        error(result, 503, "LOGIN_UNAVAILABLE", "RESTART_LOGIN");
        assertThat(result.body().get("login_request_consumed").booleanValue()).isTrue();
        assertThat(accounts.findByIdentity("google", subject)).isPresent();
    }

    @Test
    void completedConsentSurvivesLostResponseAndNextLoginNeedsNoNewConsent() {
        var current = insert(Instant.now().minusSeconds(60));
        var first = login(subject);
        var credential = first.body().get("consent_request_id").asString();
        var body = Map.of("consent_request_id", credential, "terms_version_id", current.toString());
        var completed = call("POST", "/auth/terms/accept", body, null);
        assertThat(completed.status()).isEqualTo(200);
        assertThat(completed.body().size()).isEqualTo(2);
        assertThat(completed.body().get("session_id").asString()).hasSize(43);
        // Treat the successful response as lost; the same request must not issue another session.
        error(
                call("POST", "/auth/terms/accept", body, null),
                401,
                "CONSENT_REQUEST_INVALID",
                "RESTART_LOGIN");
        var retriedLogin = login(subject);
        assertThat(retriedLogin.body().get("status").asString()).isEqualTo("AUTHENTICATED");
        assertThat(retriedLogin.body().get("session_id").asString())
                .isNotEqualTo(completed.body().get("session_id").asString());
    }

    private UUID insert(Instant effective) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO terms_versions VALUES (?, 'SERVICE_TERMS', ?, 'Terms', 'Test original', ?, ?)",
                id,
                id.toString(),
                Timestamp.from(effective),
                Timestamp.from(effective));
        originals.add(id);
        return id;
    }
}
