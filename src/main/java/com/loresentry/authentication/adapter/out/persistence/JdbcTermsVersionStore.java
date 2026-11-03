package com.loresentry.authentication.adapter.out.persistence;

import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.application.port.out.TermsVersionStore;
import com.loresentry.authentication.domain.SupportedLocale;
import com.loresentry.authentication.domain.TermsText;
import com.loresentry.authentication.domain.TermsVersion;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
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
        AppLogger.current().info("terms current version get");
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
            // 원인은 예외 클래스 이름과 스택 프레임만 남긴다(메시지에는 SQL·키·값이 섞일 수 있다). 이 실패는 PortFailure 로 바뀌어 원인이 사라진다
            AppLogger.current()
                    .warn(
                            "terms version store call failed",
                            LogField.errorType(error.getClass().getName()),
                            LogField.safeStackTrace(error));
            throw unavailable();
        }
    }

    @Override
    public Optional<TermsText> translation(UUID termsVersionId, SupportedLocale locale) {
        // 언어는 controller 진입 줄과 같은 소문자 코드(ko·en)로 남긴다
        AppLogger.current()
                .info(
                        "terms translation get",
                        LogField.param("terms_version_id", termsVersionId),
                        LogField.code(
                                "locale",
                                locale == null ? null : locale.code(),
                                Set.of("ko", "en")));
        try {
            return jdbc
                    .query(
                            "SELECT title, content FROM terms_version_translations WHERE terms_version_id = ? AND locale = ?",
                            (rs, row) ->
                                    new TermsText(
                                            locale, rs.getString("title"), rs.getString("content")),
                            termsVersionId,
                            locale.code())
                    .stream()
                    .findFirst();
        } catch (RuntimeException error) {
            // 원인은 예외 클래스 이름과 스택 프레임만 남긴다(메시지에는 SQL·키·값이 섞일 수 있다). 이 실패는 PortFailure 로 바뀌어 원인이 사라진다
            AppLogger.current()
                    .warn(
                            "terms version store call failed",
                            LogField.errorType(error.getClass().getName()),
                            LogField.safeStackTrace(error));
            throw unavailable();
        }
    }

    private PortFailure unavailable() {
        return new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false);
    }
}
