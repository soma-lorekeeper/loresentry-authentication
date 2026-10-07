package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.support.HttpAuthTestSupport;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpAuthTestSupport.GoogleHttpConfiguration.class)
class LocaleFlowTest extends HttpAuthTestSupport {
    static final String TERMS = "b226d203-1e9f-4435-8dc8-7a2dc9fcd505";
    @Autowired JdbcTemplate jdbc;
    final String subject = "locale-" + UUID.randomUUID();

    @AfterEach
    void cleanup() {
        accounts.findByIdentity("google", subject)
                .ifPresent(account -> accounts.delete(account.user().id()));
    }

    @Test
    void englishTermsAreThePublishedV0TranslationAndConsentStaysOnV0() {
        var pending = login(subject);
        assertThat(pending.body().get("status").asString()).isEqualTo("TERMS_REQUIRED");
        var consent =
                Map.of("X-Consent-Request-Id", pending.body().get("consent_request_id").asString());
        var english = call("GET", "/auth/terms?locale=en", null, null, consent);
        assertThat(english.status()).isEqualTo(200);
        assertThat(english.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(english.body().size()).isEqualTo(7);
        assertThat(english.body().get("terms_version_id").asString()).isEqualTo(TERMS);
        assertThat(english.body().get("version").asString()).isEqualTo("v0");
        assertThat(english.body().get("title").asString())
                .isEqualTo("Lore Sentry Terms of Service");
        assertThat(english.body().get("locale").asString()).isEqualTo("en");
        assertThat(english.body().get("content").asString())
                .startsWith("Article 1. Purpose and service")
                .contains("https://loresentry.com/policies/privacy.html");
        for (var path : List.of("/auth/terms", "/auth/terms?locale=ko", "/auth/terms?locale=fr")) {
            var korean = call("GET", path, null, null, consent);
            assertThat(korean.status()).isEqualTo(200);
            assertThat(korean.body().get("terms_version_id").asString()).isEqualTo(TERMS);
            assertThat(korean.body().get("title").asString()).isEqualTo("Lore Sentry 서비스 이용약관");
            assertThat(korean.body().get("locale").asString()).isEqualTo("ko");
            assertThat(korean.body().get("effective_at"))
                    .isEqualTo(english.body().get("effective_at"));
        }
        var accepted =
                call(
                        "POST",
                        "/auth/terms/accept",
                        Map.of(
                                "consent_request_id",
                                consent.get("X-Consent-Request-Id"),
                                "terms_version_id",
                                TERMS),
                        null);
        assertThat(accepted.status()).isEqualTo(200);
        var user = accounts.findByIdentity("google", subject).orElseThrow().user().id();
        assertThat(
                        jdbc.queryForList(
                                "SELECT terms_version_id::text FROM user_terms_acceptances WHERE user_id = ?",
                                String.class,
                                user))
                .containsExactly(TERMS);
        assertThat(login(subject).body().get("status").asString()).isEqualTo("AUTHENTICATED");
    }

    @Test
    void accountLocaleStartsUnsetAndOnlyKoOrEnCanBeStored() {
        login(subject);
        var user = accounts.findByIdentity("google", subject).orElseThrow().user().id();
        var initial = call("GET", "/auth/users/me", null, user);
        assertThat(initial.body().size()).isEqualTo(5);
        assertThat(initial.body().get("locale").isNull()).isTrue();
        var changed = call("PUT", "/auth/users/me/locale", Map.of("locale", "en"), user);
        assertThat(changed.status()).isEqualTo(200);
        assertThat(changed.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(changed.body().get("locale").asString()).isEqualTo("en");
        assertThat(changed.body()).isEqualTo(call("GET", "/auth/users/me", null, user).body());
        for (var body :
                Arrays.<Object>asList(
                        null,
                        Map.of(),
                        Collections.singletonMap("locale", null),
                        Map.of("locale", "EN"),
                        Map.of("locale", "ja"),
                        Map.of("locale", ""),
                        Map.of("locale", " ko"),
                        Map.of("locale", 1),
                        Map.of("locale", "ko", "display_name", "Name")))
            error(call("PUT", "/auth/users/me/locale", body, user), 400, "INVALID_REQUEST", "NONE");
        var renamed = call("PATCH", "/auth/users/me", Map.of("display_name", "Renamed"), user);
        assertThat(renamed.body().get("locale").asString()).isEqualTo("en");
        assertThat(
                        call("PUT", "/auth/users/me/locale", Map.of("locale", "ko"), user)
                                .body()
                                .get("locale")
                                .asString())
                .isEqualTo("ko");
        error(
                call("PUT", "/auth/users/me/locale", Map.of("locale", "ko"), UUID.randomUUID()),
                404,
                "USER_NOT_FOUND",
                "RELOGIN");
        error(
                call("PUT", "/auth/users/me/locale", Map.of("locale", "ko"), null),
                401,
                "USER_CONTEXT_REQUIRED",
                "RELOGIN");
    }

    @Test
    void namelessGoogleAccountUsesTheEmailLocalPart() {
        var result =
                callback(
                        pending(
                                subject,
                                claims ->
                                        claims.claim("name", "   ")
                                                .claim("email", "quiet.writer@example.test")));
        assertThat(result.status()).isEqualTo(200);
        var user = accounts.findByIdentity("google", subject).orElseThrow().user().id();
        assertThat(call("GET", "/auth/users/me", null, user).body().get("display_name").asString())
                .isEqualTo("quiet.writer");
    }
}
