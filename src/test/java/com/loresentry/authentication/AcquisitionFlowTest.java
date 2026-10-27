package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.support.HttpAuthTestSupport;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpAuthTestSupport.GoogleHttpConfiguration.class)
class AcquisitionFlowTest extends HttpAuthTestSupport {
    @Autowired JdbcTemplate jdbc;
    final String subject = "acquisition-" + UUID.randomUUID();

    @AfterEach
    void cleanup() {
        accounts.findByIdentity("google", subject)
                .ifPresent(account -> accounts.delete(account.user().id()));
    }

    @Test
    void newAccountKeepsItsFirstSourceUntilTheAccountIsDeleted() {
        var user = newAccount();
        var first = new LinkedHashMap<String, Object>();
        first.put("utm_source", "google");
        first.put("utm_medium", "cpc");
        first.put("utm_campaign", "23145678901");
        first.put("utm_term", "소설 에디터");
        first.put("utm_content", " ");
        first.put("click_id", "Cj0KCQjw-abc_DEF");
        first.put("click_id_type", "gclid");
        first.put("referrer_host", "www.google.com");
        first.put("landing_path", "/");
        first.put("landed_at", "2026-10-27T01:02:03.456789Z");
        var recorded = call("PUT", "/auth/users/me/acquisition", first, user);
        assertThat(recorded.status()).isEqualTo(204);
        assertThat(recorded.body()).isNull();
        assertThat(recorded.headers().firstValue("Cache-Control")).contains("no-store");
        var row = row(user);
        assertThat(row)
                .containsEntry("utm_source", "google")
                .containsEntry("utm_medium", "cpc")
                .containsEntry("utm_campaign", "23145678901")
                .containsEntry("utm_term", "소설 에디터")
                .containsEntry("utm_content", null)
                .containsEntry("click_id", "Cj0KCQjw-abc_DEF")
                .containsEntry("click_id_type", "gclid")
                .containsEntry("referrer_host", "www.google.com")
                .containsEntry("landing_path", "/");
        assertThat(((java.sql.Timestamp) row.get("landed_at")).toInstant())
                .isEqualTo(Instant.parse("2026-10-27T01:02:03.456789Z"));
        assertThat(row.get("recorded_at")).isNotNull();

        assertThat(
                        call(
                                        "PUT",
                                        "/auth/users/me/acquisition",
                                        Map.of("utm_source", "naver", "landing_path", "/en/"),
                                        user)
                                .status())
                .isEqualTo(204);
        assertThat(row(user)).isEqualTo(row);

        assertThat(call("DELETE", "/auth/users/me", null, user).status()).isEqualTo(204);
        assertThat(count(user)).isZero();
    }

    @Test
    void directVisitIsRecordedAsARowWithoutSource() {
        var user = newAccount();
        assertThat(call("PUT", "/auth/users/me/acquisition", Map.of(), user).status())
                .isEqualTo(204);
        var row = row(user);
        assertThat(row.entrySet())
                .filteredOn(entry -> !entry.getKey().equals("recorded_at"))
                .allSatisfy(entry -> assertThat(entry.getValue()).isNull());
        assertThat(row.get("recorded_at")).isNotNull();
    }

    @Test
    void sourceIsNotRecordedOnceOnboardingIsComplete() {
        var user = newAccount();
        assertThat(call("PUT", "/auth/users/me/onboarding", null, user).status()).isEqualTo(204);
        assertThat(
                        call(
                                        "PUT",
                                        "/auth/users/me/acquisition",
                                        Map.of("utm_source", "google"),
                                        user)
                                .status())
                .isEqualTo(204);
        assertThat(count(user)).isZero();
    }

    @Test
    void missingAccountAndMissingUserContextAreRejected() {
        error(
                call("PUT", "/auth/users/me/acquisition", Map.of(), UUID.randomUUID()),
                404,
                "USER_NOT_FOUND",
                "RELOGIN");
        error(
                call("PUT", "/auth/users/me/acquisition", Map.of(), null),
                401,
                "USER_CONTEXT_REQUIRED",
                "RELOGIN");
    }

    private UUID newAccount() {
        login(subject);
        return accounts.findByIdentity("google", subject).orElseThrow().user().id();
    }

    private Map<String, Object> row(UUID user) {
        return jdbc.queryForMap(
                "SELECT utm_source, utm_medium, utm_campaign, utm_term, utm_content, click_id, click_id_type, referrer_host, landing_path, landed_at, recorded_at FROM user_acquisitions WHERE user_id = ?",
                user);
    }

    private long count(UUID user) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM user_acquisitions WHERE user_id = ?", Long.class, user);
    }
}
