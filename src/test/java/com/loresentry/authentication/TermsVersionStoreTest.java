package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.application.port.out.TermsVersionStore;
import com.loresentry.authentication.support.DatabaseTestSupport;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class TermsVersionStoreTest extends DatabaseTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired TermsVersionStore terms;
    final List<UUID> inserted = new ArrayList<>();

    @AfterEach
    void cleanup() {
        inserted.forEach(id -> jdbc.update("DELETE FROM terms_versions WHERE id = ?", id));
    }

    @Test
    void effectiveTimeSelectsVersionIncludingExactBoundaryWithoutMutatingOriginal() {
        var start = Instant.parse("2100-01-01T00:00:00Z");
        var first = insert("z-" + UUID.randomUUID(), start, start);
        var second = insert("a-" + UUID.randomUUID(), start, start.plusSeconds(60));
        assertThat(terms.current(start).orElseThrow().id()).isEqualTo(first);
        assertThat(terms.current(start.plusSeconds(59)).orElseThrow().id()).isEqualTo(first);
        var current = terms.current(start.plusSeconds(60)).orElseThrow();
        assertThat(current.id()).isEqualTo(second);
        assertThat(current.publishedAt()).isEqualTo(start);
        assertThat(current.effectiveAt()).isEqualTo(start.plusSeconds(60));
        assertThat(current.content()).isEqualTo("Original\ntext");
        assertThat(terms.current(start.plusSeconds(120))).contains(current);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT published_at = effective_at FROM terms_versions WHERE id = ?",
                                Boolean.class,
                                first))
                .isTrue();
    }

    @Test
    void noApplicableOriginalIsDistinctFromStoreFailure() {
        var start = Instant.parse("1900-01-01T00:00:00Z");
        insert(UUID.randomUUID().toString(), start.plusSeconds(30), start.plusSeconds(60));
        assertThat(terms.current(start)).isEmpty();
        assertThat(terms.current(start.plusSeconds(30))).isEmpty();
        assertThat(terms.current(start.plusSeconds(60))).isPresent();
    }

    private UUID insert(String version, Instant published, Instant effective) {
        var id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO terms_versions VALUES (?, 'SERVICE_TERMS', ?, 'Test terms', ?, ?, ?)",
                id,
                version,
                "Original\ntext",
                Timestamp.from(published),
                Timestamp.from(effective));
        inserted.add(id);
        return id;
    }
}
