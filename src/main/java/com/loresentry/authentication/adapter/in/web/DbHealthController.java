package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class DbHealthController {

    private static final String SERVICE = "authentication-api";

    private final JdbcClient jdbcClient;

    @GetMapping("/health/db")
    public ResponseEntity<Map<String, Object>> databaseHealth() {
        AppLogger.current().info("database health check requested");
        try {
            Map<String, Object> row =
                    jdbcClient.sql("select current_database() as database").query().singleRow();

            return ResponseEntity.ok(
                    Map.of(
                            "status",
                            "ok",
                            "service",
                            SERVICE,
                            // DB 사용자 이름·버전은 싣지 않는다. 로그인한 사용자도 gateway /health/db 로 이 본문을 볼 수 있다
                            "database",
                            row.get("database")));
        } catch (DataAccessException exception) {
            // 응답 본문은 그대로 두고, 원인(클래스 이름과 프레임만)은 여기서 남기고 이유는 결과 줄(request completed)에 맡긴다
            AppLogger.current()
                    .warn(
                            "database health check failed",
                            LogField.errorType(exception.getClass().getName()),
                            LogField.safeStackTrace(exception));
            AppLogger.current().addToOutcome(LogField.errorType("DATABASE_UNAVAILABLE"));
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(
                            Map.of(
                                    "status", "error",
                                    "service", SERVICE,
                                    "error", "Database unavailable"));
        }
    }
}
