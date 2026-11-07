package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.domain.SessionId;
import com.loresentry.authentication.support.*;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpAuthTestSupport.GoogleHttpConfiguration.class)
class PasswordFlowTest extends HttpAuthTestSupport {
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @Test
    void signUpRequiresTermsThenLoginIssuesSessionsAndDeletionRemovesTheCredential() {
        var loginId = "flow" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        var password = "correct-horse-7";

        var signedUp =
                call(
                        "POST",
                        "/auth/password/signup",
                        Map.of("login_id", loginId.toUpperCase(), "password", password),
                        null);
        assertThat(signedUp.status()).isEqualTo(200);
        assertThat(signedUp.body().get("status").asString()).isEqualTo("TERMS_REQUIRED");
        assertThat(signedUp.body().has("session_id")).isFalse();
        var consent = signedUp.body().get("consent_request_id").asString();

        var account = accounts.findByIdentity("password", loginId).orElseThrow();
        assertThat(account.user().displayName()).isEqualTo(loginId);
        assertThat(account.identity().email()).isNull();
        var stored =
                jdbc.queryForObject(
                        "SELECT password_hash FROM password_credentials WHERE user_id = ?",
                        String.class,
                        account.user().id());
        assertThat(stored).startsWith("$2").doesNotContain(password);

        var terms = call("GET", "/auth/terms", null, null, Map.of("X-Consent-Request-Id", consent));
        assertThat(terms.status()).isEqualTo(200);
        var accepted =
                call(
                        "POST",
                        "/auth/terms/accept",
                        Map.of(
                                "consent_request_id",
                                consent,
                                "terms_version_id",
                                terms.body().get("terms_version_id").asString()),
                        null);
        assertThat(accepted.status()).isEqualTo(200);
        var first = new SessionId(accepted.body().get("session_id").asString());
        assertThat(redis.opsForValue().get("auth:session:{login}:by-id:" + first.hash()))
                .contains(account.user().id().toString());

        var duplicate =
                call(
                        "POST",
                        "/auth/password/signup",
                        Map.of("login_id", loginId, "password", password),
                        null);
        error(duplicate, 409, "LOGIN_ID_TAKEN", "NONE");

        var wrong =
                call(
                        "POST",
                        "/auth/password/login",
                        Map.of("login_id", loginId, "password", "not-the-password"),
                        null);
        error(wrong, 401, "INVALID_CREDENTIALS", "NONE");

        var loggedIn =
                call(
                        "POST",
                        "/auth/password/login",
                        Map.of("login_id", loginId, "password", password),
                        null);
        assertThat(loggedIn.status()).isEqualTo(200);
        assertThat(loggedIn.body().get("status").asString()).isEqualTo("AUTHENTICATED");
        var second = new SessionId(loggedIn.body().get("session_id").asString());
        assertThat(second).isNotEqualTo(first);
        assertThat(redis.opsForValue().get("auth:session:{login}:by-user:" + account.user().id()))
                .contains(second.hash())
                .doesNotContain(first.hash());

        var profile = call("GET", "/auth/users/me", null, account.user().id());
        assertThat(profile.status()).isEqualTo(200);
        assertThat(profile.body().get("email").isNull()).isTrue();
        assertThat(profile.body().get("onboarding_completed").booleanValue()).isFalse();

        var deleted = call("DELETE", "/auth/users/me", null, account.user().id());
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM password_credentials WHERE user_id = ?",
                                Integer.class,
                                account.user().id()))
                .isZero();
        error(
                call(
                        "POST",
                        "/auth/password/login",
                        Map.of("login_id", loginId, "password", password),
                        null),
                401,
                "INVALID_CREDENTIALS",
                "NONE");
        assertThat(google.unexpectedCalls.get()).isZero();
    }

    @Test
    void invalidInputIsRejectedBeforeAnythingIsStored() {
        error(
                call(
                        "POST",
                        "/auth/password/signup",
                        Map.of("login_id", "no spaces", "password", "correct-horse-7"),
                        null),
                400,
                "INVALID_LOGIN_ID",
                "NONE");
        error(
                call(
                        "POST",
                        "/auth/password/signup",
                        Map.of("login_id", "shortpw", "password", "short"),
                        null),
                400,
                "INVALID_PASSWORD",
                "NONE");
        assertThat(accounts.findByIdentity("password", "shortpw")).isEmpty();
    }
}
