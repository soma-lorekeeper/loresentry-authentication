package com.loresentry.authentication.adapter.in.web;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.out.ConsentRequestStore;
import com.loresentry.authentication.domain.ConsentId;
import java.time.Clock;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** Internal BFF endpoint: claim a newly created account's pending browser measurement once. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/auth/analytics/registration")
public class RegistrationAnalyticsController {
    private final JdbcTemplate jdbc;
    private final ConsentRequestStore requests;
    private final Clock clock;

    public record Result(boolean registered) {}

    @PostMapping("/user")
    public ResponseEntity<Result> user(@RequestHeader("X-User-Id") String raw) {
        UUID id;
        try {
            id = UUID.fromString(raw);
            if (!id.toString().equalsIgnoreCase(raw)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException invalid) {
            throw new AuthFailure(AuthFailure.Reason.INVALID_REQUEST);
        }
        return claim(id);
    }

    @PostMapping("/consent")
    public ResponseEntity<Result> consent(@RequestHeader("X-Consent-Request-Id") String raw) {
        ConsentId id;
        try {
            id = new ConsentId(raw);
        } catch (IllegalArgumentException invalid) {
            throw new AuthFailure(AuthFailure.Reason.CONSENT_REQUEST_INVALID);
        }
        var pending =
                requests.find(id)
                        .filter(value -> value.expiresAt().isAfter(clock.instant()))
                        .orElseThrow(
                                () -> new AuthFailure(AuthFailure.Reason.CONSENT_REQUEST_INVALID));
        return claim(pending.userId());
    }

    private ResponseEntity<Result> claim(UUID id) {
        int count = jdbc.update("DELETE FROM signup_analytics_pending WHERE user_id = ?", id);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Result(count == 1));
    }
}
