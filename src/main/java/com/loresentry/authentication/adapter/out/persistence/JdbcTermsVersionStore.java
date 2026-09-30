package com.loresentry.authentication.adapter.out.persistence;

import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.application.port.out.TermsVersionStore;
import com.loresentry.authentication.domain.TermsVersion;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class JdbcTermsVersionStore implements TermsVersionStore {
    private final JdbcTemplate jdbc;

    @Override
    public Optional<TermsVersion> current(Instant now) {
        try {
            return jdbc
                    .query(
                            "SELECT id, version, title, content, published_at, effective_at FROM terms_versions WHERE terms_type = 'SERVICE_TERMS' AND published_at <= ? AND effective_at <= ? ORDER BY effective_at DESC LIMIT 1",
                            (rs, row) ->
                                    new TermsVersion(
                                            rs.getObject("id", UUID.class),
                                            rs.getString("version"),
                                            rs.getString("title"),
                                            rs.getString("content"),
                                            rs.getTimestamp("published_at").toInstant(),
                                            rs.getTimestamp("effective_at").toInstant()),
                            Timestamp.from(now),
                            Timestamp.from(now))
                    .stream()
                    .findFirst();
        } catch (RuntimeException error) {
            throw new PortFailure(
                    PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false);
        }
    }
}
