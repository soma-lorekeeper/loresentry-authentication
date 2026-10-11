package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.TermsQueryUseCase;
import com.loresentry.authentication.domain.SessionId;
import com.loresentry.authentication.support.HttpAuthTestSupport;
import java.sql.Timestamp;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpAuthTestSupport.GoogleHttpConfiguration.class)
class AccountDeletionFlowTest extends HttpAuthTestSupport {
    static final String TERMS = "b226d203-1e9f-4435-8dc8-7a2dc9fcd505";
    static final String SESSIONS = "auth:session:{login}:";
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired TermsQueryUseCase terms;
    final String subject = "withdrawal-" + UUID.randomUUID();

    @AfterEach
    void cleanup() {
        accounts.findByIdentity("google", subject)
                .ifPresent(account -> accounts.delete(account.user().id()));
    }

    @Test
    void newAccountCompletesOnboardingOnceAndKeepsTheFirstTime() {
        var user = acceptedLogin().user();
        var profile = call("GET", "/auth/users/me", null, user);
        assertThat(profile.status()).isEqualTo(200);
        assertThat(profile.body().size()).isEqualTo(5);
        assertThat(profile.body().get("onboarding_completed").booleanValue()).isFalse();
        assertThat(profile.body().get("locale").isNull()).isTrue();
        var completed = call("PUT", "/auth/users/me/onboarding", null, user);
        assertThat(completed.status()).isEqualTo(204);
        assertThat(completed.headers().firstValue("Cache-Control")).contains("no-store");
        var first = completedAt(user);
        assertThat(first).isNotNull();
        assertThat(call("PUT", "/auth/users/me/onboarding", null, user).status()).isEqualTo(204);
        assertThat(completedAt(user)).isEqualTo(first);
        assertThat(
                        call("GET", "/auth/users/me", null, user)
                                .body()
                                .get("onboarding_completed")
                                .booleanValue())
                .isTrue();
        var renamed =
                call("PATCH", "/auth/users/me", Map.of("display_name", "Renamed"), user).body();
        assertThat(renamed.get("onboarding_completed").booleanValue()).isTrue();
        error(
                call("PUT", "/auth/users/me/onboarding", null, UUID.randomUUID()),
                404,
                "USER_NOT_FOUND",
                "RELOGIN");
    }

    @Test
    void withdrawalRevokesTheSessionAndDeletesTheAccountWithoutSharedTerms() {
        var accepted = acceptedLogin();
        var user = accepted.user();
        assertThat(count("user_terms_acceptances", user)).isOne();
        var deleted = call("DELETE", "/auth/users/me", null, user);
        assertThat(deleted.status()).isEqualTo(204);
        assertThat(deleted.body()).isNull();
        assertThat(deleted.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(redis.hasKey(SESSIONS + "by-user:" + user)).isFalse();
        assertThat(redis.hasKey(SESSIONS + "by-id:" + accepted.session().hash())).isFalse();
        assertThat(count("user_terms_acceptances", user)).isZero();
        assertThat(count("oauth_identities", user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Long.class, user))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM terms_versions WHERE id = ?::uuid",
                                Long.class,
                                TERMS))
                .isOne();
        error(call("DELETE", "/auth/users/me", null, user), 404, "USER_NOT_FOUND", "RELOGIN");
        error(call("GET", "/auth/users/me", null, user), 404, "USER_NOT_FOUND", "RELOGIN");

        var next = login(subject);
        assertThat(next.body().get("status").asString()).isEqualTo("TERMS_REQUIRED");
        var fresh = accounts.findByIdentity("google", subject).orElseThrow().user();
        assertThat(fresh.id()).isNotEqualTo(user);
        assertThat(fresh.onboardingCompletedAt()).isNull();
    }

    /**
     * 탈퇴하면 그 계정의 UserDeleted 이벤트가 outbox 에 정확히 하나 남는다. 삭제와 같은 트랜잭션에서 기록되므로, 계정이 지워졌다면 이벤트도 반드시 있다.
     * 이미 없는 계정을 다시 지우면 지운 것이 없으므로 이벤트도 늘지 않는다.
     *
     * <p>다른 테스트도 계정을 지우며 outbox 에 행을 남기므로, 이 사용자의 행만 골라서 본다.
     */
    @Test
    void withdrawalRecordsExactlyOneUserDeletedEventForThatAccount() {
        var user = acceptedLogin().user();
        assertThat(events(user)).isEmpty();

        assertThat(call("DELETE", "/auth/users/me", null, user).status()).isEqualTo(204);

        var events = events(user);
        assertThat(events).hasSize(1);
        var event = events.getFirst();
        assertThat(event.get("event_type")).isEqualTo("UserDeleted");
        // Kafka 의 key 와 이벤트의 대상은 둘 다 사용자 id 다.
        assertThat(event.get("event_key")).isEqualTo(user.toString());
        assertThat(event.get("aggregate_id")).isEqualTo(user);
        // payload 는 계약의 {"user_id": "…"} 하나뿐이다. 이메일이나 이름은 싣지 않는다.
        assertThat(event.get("user_id")).isEqualTo(user.toString());
        assertThat(event.get("fields")).isEqualTo(1L);
        // 아직 아무도 보내지 않은 상태로 남는다. 보내는 것은 relay 의 일이다.
        assertThat(event.get("published_at")).isNull();
        assertThat(event.get("quarantined_at")).isNull();
        assertThat(event.get("attempts")).isEqualTo(0);

        error(call("DELETE", "/auth/users/me", null, user), 404, "USER_NOT_FOUND", "RELOGIN");
        assertThat(events(user)).hasSize(1);
    }

    @Test
    void pendingConsentOfAWithdrawnAccountCannotCompleteIntoASession() {
        var pending = login(subject);
        assertThat(pending.body().get("status").asString()).isEqualTo("TERMS_REQUIRED");
        var consent = pending.body().get("consent_request_id").asString();
        var user = accounts.findByIdentity("google", subject).orElseThrow().user().id();
        assertThat(call("DELETE", "/auth/users/me", null, user).status()).isEqualTo(204);

        assertThatThrownBy(() -> terms.query(consent, null))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(AuthFailure.Reason.CONSENT_REQUEST_INVALID));
        error(
                call(
                        "POST",
                        "/auth/terms/accept",
                        Map.of("consent_request_id", consent, "terms_version_id", TERMS),
                        null),
                401,
                "CONSENT_REQUEST_INVALID",
                "RESTART_LOGIN");
        assertThat(redis.hasKey(SESSIONS + "by-user:" + user)).isFalse();
        assertThat(count("user_terms_acceptances", user)).isZero();
        assertThat(accounts.findById(user)).isEmpty();

        var relogin = login(subject);
        assertThat(relogin.body().get("status").asString()).isEqualTo("TERMS_REQUIRED");
        assertThat(relogin.body().get("consent_request_id").asString()).isNotEqualTo(consent);
        assertThat(accounts.findByIdentity("google", subject).orElseThrow().user().id())
                .isNotEqualTo(user);
    }

    private record Accepted(UUID user, SessionId session) {}

    private Accepted acceptedLogin() {
        var pending = login(subject);
        assertThat(pending.body().get("status").asString()).isEqualTo("TERMS_REQUIRED");
        var accepted =
                call(
                        "POST",
                        "/auth/terms/accept",
                        Map.of(
                                "consent_request_id",
                                pending.body().get("consent_request_id").asString(),
                                "terms_version_id",
                                TERMS),
                        null);
        assertThat(accepted.status()).isEqualTo(200);
        var user = accounts.findByIdentity("google", subject).orElseThrow().user().id();
        var session = new SessionId(accepted.body().get("session_id").asString());
        assertThat(redis.opsForValue().get(SESSIONS + "by-user:" + user)).contains(session.hash());
        return new Accepted(user, session);
    }

    private long count(String table, UUID user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE user_id = ?", Long.class, user);
    }

    /** 이 사용자를 대상으로 하는 outbox 행. payload 는 user_id 의 값과 필드 수로 풀어서 돌려준다. */
    private List<Map<String, Object>> events(UUID user) {
        return jdbc.queryForList(
                "SELECT event_type, event_key, aggregate_id, payload ->> 'user_id' AS user_id, "
                        + "(SELECT count(*) FROM jsonb_object_keys(payload)) AS fields, "
                        + "published_at, quarantined_at, attempts "
                        + "FROM outbox_events WHERE aggregate_id = ?",
                user);
    }

    private Timestamp completedAt(UUID user) {
        return jdbc.queryForObject(
                "SELECT onboarding_completed_at FROM users WHERE id = ?", Timestamp.class, user);
    }
}
