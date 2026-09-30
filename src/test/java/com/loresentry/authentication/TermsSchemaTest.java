package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.application.port.out.TermsAcceptanceStore;
import com.loresentry.authentication.support.DatabaseTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TermsSchemaTest extends DatabaseTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired TermsAcceptanceStore acceptances;
    final UUID user = UUID.randomUUID();
    final UUID terms = UUID.randomUUID();

    @BeforeEach
    void seed() {
        jdbc.update(
                "INSERT INTO users(id, display_name, created_at, updated_at) VALUES (?, 'terms-test', now(), now())",
                user);
        jdbc.update(
                "INSERT INTO terms_versions VALUES (?, 'SERVICE_TERMS', ?, 'Test terms', 'Test original only', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                terms,
                terms.toString());
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM users WHERE id = ?", user);
        jdbc.update("DELETE FROM terms_versions WHERE id = ?", terms);
    }

    @Test
    void acceptanceCommitsOnceAndAccountDeletionPreservesOriginal() {
        var first = Instant.parse("2026-09-30T00:00:00Z");
        assertThat(acceptances.hasAccepted(user, terms)).isFalse();
        acceptances.accept(user, terms, first);
        acceptances.accept(user, terms, first.plusSeconds(10));
        assertThat(acceptances.hasAccepted(user, terms)).isTrue();
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT accepted_at FROM user_terms_acceptances WHERE user_id = ? AND terms_version_id = ?",
                                        Timestamp.class,
                                        user,
                                        terms)
                                .toInstant())
                .isEqualTo(first);
        jdbc.update("DELETE FROM users WHERE id = ?", user);
        assertThat(acceptances.hasAccepted(user, terms)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT content FROM terms_versions WHERE id = ?",
                                String.class,
                                terms))
                .isEqualTo("Test original only");
    }

    @Test
    void constraintsRejectDuplicateVersionsTimesAndInvalidPublication() {
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO terms_versions SELECT ?, terms_type, version, title, content, published_at, effective_at + interval '1 second' FROM terms_versions WHERE id = ?",
                                        UUID.randomUUID(),
                                        terms))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "INSERT INTO terms_versions SELECT ?, terms_type, ?, title, content, published_at, effective_at FROM terms_versions WHERE id = ?",
                                        UUID.randomUUID(),
                                        UUID.randomUUID().toString(),
                                        terms))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE terms_versions SET published_at = effective_at + interval '1 second' WHERE id = ?",
                                        terms))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
                        () ->
                                jdbc.update(
                                        "UPDATE terms_versions SET content = NULL WHERE id = ?",
                                        terms))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT published_at = effective_at FROM terms_versions WHERE id = ?",
                                Boolean.class,
                                terms))
                .isTrue();
    }

    @Test
    void invalidReferencesFailWithoutLeavingPartialAcceptance() {
        assertThatThrownBy(() -> acceptances.accept(user, UUID.randomUUID(), Instant.now()))
                .isInstanceOf(PortFailure.class)
                .hasMessage("UNAVAILABLE");
        assertThatThrownBy(() -> acceptances.accept(UUID.randomUUID(), terms, Instant.now()))
                .isInstanceOf(PortFailure.class);
        assertThat(acceptances.hasAccepted(user, terms)).isFalse();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM users WHERE id = ?", Integer.class, user))
                .isEqualTo(1);
        acceptances.accept(user, terms, Instant.now());
        assertThatThrownBy(() -> jdbc.update("DELETE FROM terms_versions WHERE id = ?", terms))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
