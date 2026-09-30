package com.loresentry.authentication.adapter.out.persistence;

import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.application.port.out.TermsAcceptanceStore;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@RequiredArgsConstructor
public class JdbcTermsAcceptanceStore implements TermsAcceptanceStore {
    private final JdbcTemplate jdbc;
    private final PlatformTransactionManager transactionManager;

    @Override
    public boolean hasAccepted(UUID userId, UUID termsVersionId) {
        try {
            return Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM user_terms_acceptances WHERE user_id = ? AND terms_version_id = ?)",
                            Boolean.class,
                            userId,
                            termsVersionId));
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    @Override
    public void accept(UUID userId, UUID termsVersionId, Instant acceptedAt) {
        try {
            var transaction = new TransactionTemplate(transactionManager);
            transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            transaction.executeWithoutResult(
                    status ->
                            jdbc.update(
                                    "INSERT INTO user_terms_acceptances(user_id, terms_version_id, accepted_at) VALUES (?, ?, ?) ON CONFLICT (user_id, terms_version_id) DO NOTHING",
                                    userId,
                                    termsVersionId,
                                    Timestamp.from(acceptedAt)));
        } catch (RuntimeException error) {
            throw unavailable();
        }
    }

    private PortFailure unavailable() {
        return new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false);
    }
}
