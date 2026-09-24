package com.loresentry.authentication.application.service;

import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import java.time.Clock;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * RT를 검증하고 삭제 결과가 확인될 때까지 제한적으로 폐기를 재시도한다.
 *
 * <p>삭제는 최대 3회 시도하며 100ms, 200ms 대기와 각 삭제 호출의 500ms 제한을 고려해 삭제 단계의 2초 예산 안에서 실행한다. 재시도 불가 실패나 결과
 * 미확인은 폐기 실패로 보고한다.
 */
public final class RevokeService implements RevokeUseCase {
    private final JwtTokens jwt;
    private final SessionStore store;
    private final Clock clock;
    private final LongSupplier nanos;
    private final LongConsumer pause;

    public RevokeService(JwtTokens jwt, SessionStore store, Clock clock) {
        this(
                jwt,
                store,
                clock,
                System::nanoTime,
                millis -> {
                    try {
                        Thread.sleep(millis);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AuthFailure(REVOCATION_UNCONFIRMED);
                    }
                });
    }

    public RevokeService(
            JwtTokens jwt,
            SessionStore store,
            Clock clock,
            LongSupplier nanos,
            LongConsumer pause) {
        this.jwt = jwt;
        this.store = store;
        this.clock = clock;
        this.nanos = nanos;
        this.pause = pause;
    }

    public void revoke(String token) {
        JwtTokens.RefreshClaims claims;
        try {
            claims = jwt.verifyRefresh(token, true);
        } catch (PortFailure e) {
            throw new AuthFailure(INVALID_REFRESH_TOKEN);
        }
        if (!clock.instant().isBefore(claims.expiresAt())) return;
        long started = nanos.getAsLong();
        for (int attempt = 0; attempt < 3; attempt++) {
            long waitMillis = attempt * 100L;
            if (nanos.getAsLong() - started + waitMillis * 1_000_000 > 1_500_000_000L) break;
            if (attempt > 0) pause.accept(waitMillis);
            // Reserve the complete port deadline; never start an attempt that can exceed the total
            // budget.
            if (nanos.getAsLong() - started > 1_500_000_000L) break;
            if (!clock.instant().isBefore(claims.expiresAt())) return;
            try {
                store.revoke(claims.userId(), claims.sid(), claims.expiresAt());
                return;
            } catch (PortFailure e) {
                if (e.kind() == PortFailure.Kind.INVALID_DATA)
                    throw new AuthFailure(INTERNAL_ERROR);
                if (!e.retryable()) break;
            }
        }
        throw new AuthFailure(REVOCATION_UNCONFIRMED);
    }
}
