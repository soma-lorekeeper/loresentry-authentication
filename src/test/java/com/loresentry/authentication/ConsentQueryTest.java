package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.loresentry.authentication.adapter.out.id.SecureSessionIds;
import com.loresentry.authentication.application.port.out.ConsentRequestStore;
import com.loresentry.authentication.domain.ConsentId;
import com.loresentry.authentication.support.DatabaseTestSupport;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class ConsentQueryTest extends DatabaseTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;
    @Autowired ConsentRequestStore requests;
    @Autowired MockMvc mvc;
    final UUID user = UUID.randomUUID();
    final UUID version = UUID.randomUUID();
    final ConsentId id = new ConsentId(new SecureSessionIds().generate().value());

    String key() {
        return "auth:consent:by-id:" + id.hash();
    }

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO users VALUES (?, 'Consent user', now(), now())", user);
        jdbc.update(
                "INSERT INTO oauth_identities(provider, provider_id, user_id) VALUES ('google', ?, ?)",
                user.toString(),
                user);
        var effective = Timestamp.from(Instant.now().minusSeconds(1));
        jdbc.update(
                "INSERT INTO terms_versions VALUES (?, 'SERVICE_TERMS', ?, 'Terms', ?, ?, ?)",
                version,
                version.toString(),
                "Original\ntext",
                effective,
                effective);
    }

    @AfterEach
    void cleanup() {
        redis.delete(key());
        jdbc.update("DELETE FROM oauth_identities WHERE user_id = ?", user);
        jdbc.update("DELETE FROM users WHERE id = ?", user);
        jdbc.update("DELETE FROM terms_versions WHERE id = ?", version);
    }

    @Test
    void returnsOnlyTermsAndUpdatesTargetWithoutExtendingAbsoluteExpiry() throws Exception {
        var pending = requests.create(id, user, UUID.randomUUID()).orElseThrow();
        assertThat(Duration.between(pending.createdAt(), pending.expiresAt()))
                .isEqualTo(Duration.ofMinutes(30));
        assertThat(requests.create(id, UUID.randomUUID(), version)).isEmpty();
        assertThat(redis.opsForValue().get(key())).doesNotContain(id.value(), "email", "name");
        redis.expire(key(), Duration.ofSeconds(10));
        long before = redis.getExpire(key(), TimeUnit.MILLISECONDS);
        mvc.perform(get("/auth/terms").header("X-Consent-Request-Id", id.value()))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(6))
                .andExpect(jsonPath("$.terms_version_id").value(version.toString()))
                .andExpect(jsonPath("$.content").value("Original\ntext"))
                .andExpect(jsonPath("$.expires_at").value(pending.expiresAt().toString()));
        assertThat(redis.getExpire(key(), TimeUnit.MILLISECONDS))
                .isPositive()
                .isLessThanOrEqualTo(before);
        assertThat(requests.find(id).orElseThrow().termsVersionId()).isEqualTo(version);
        assertThat(redis.hasKey("auth:session:{login}:by-id:" + id.hash())).isFalse();
    }

    @Test
    void invalidAbsentExpiredOrDeletedAccountCannotQueryAndRefreshNeverRecreates()
            throws Exception {
        mvc.perform(get("/auth/terms")).andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/terms").header("X-Consent-Request-Id", "bad"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/auth/terms").header("X-Consent-Request-Id", id.value()))
                .andExpect(status().isUnauthorized());
        requests.create(id, user, version);
        redis.expire(key(), Duration.ZERO);
        assertThat(requests.refreshVersion(id, user, version)).isEmpty();
        assertThat(redis.hasKey(key())).isFalse();
        requests.create(id, UUID.randomUUID(), version);
        mvc.perform(get("/auth/terms").header("X-Consent-Request-Id", id.value()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CONSENT_REQUEST_INVALID"));
    }

    @Test
    void missingApplicableTermsAndCorruptRedisStateFailClosed() throws Exception {
        requests.create(id, user, version);
        jdbc.update("DELETE FROM terms_versions WHERE id = ?", version);
        mvc.perform(get("/auth/terms").header("X-Consent-Request-Id", id.value()))
                .andExpect(status().isServiceUnavailable());
        redis.opsForValue().set(key(), "{}", Duration.ofMinutes(1));
        mvc.perform(get("/auth/terms").header("X-Consent-Request-Id", id.value()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("LOGIN_UNAVAILABLE"));
    }
}
