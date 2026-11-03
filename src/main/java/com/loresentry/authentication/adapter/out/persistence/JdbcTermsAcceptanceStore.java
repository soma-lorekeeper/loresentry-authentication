package com.loresentry.authentication.adapter.out.persistence;

import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.application.port.out.TermsAcceptanceStore;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
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
        AppLogger.current()
                .info("terms acceptance check", LogField.param("terms_version_id", termsVersionId));
        try {
            return Boolean.TRUE.equals(
                    jdbc.queryForObject(
                            "SELECT EXISTS (SELECT 1 FROM user_terms_acceptances WHERE user_id = ? AND terms_version_id = ?)",
                            Boolean.class,
                            userId,
                            termsVersionId));
        } catch (RuntimeException error) {
            // 원인은 예외 클래스 이름과 스택 프레임만 남긴다(메시지에는 SQL·키·값이 섞일 수 있다). 이 실패는 PortFailure 로 바뀌어 원인이 사라진다
            AppLogger.current()
                    .warn(
                            "terms acceptance store call failed",
                            LogField.errorType(error.getClass().getName()),
                            LogField.safeStackTrace(error));
            throw unavailable();
        }
    }

    @Override
    public void accept(UUID userId, UUID termsVersionId, Instant acceptedAt) {
        AppLogger.current()
                .info("terms acceptance save", LogField.param("terms_version_id", termsVersionId));
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
            // 원인은 예외 클래스 이름과 스택 프레임만 남긴다(메시지에는 SQL·키·값이 섞일 수 있다). 이 실패는 PortFailure 로 바뀌어 원인이 사라진다
            AppLogger.current()
                    .warn(
                            "terms acceptance store call failed",
                            LogField.errorType(error.getClass().getName()),
                            LogField.safeStackTrace(error));
            throw unavailable();
        }
    }

    private PortFailure unavailable() {
        return new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false);
    }
}
