package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import java.time.Clock;
import java.util.function.LongSupplier;
import java.util.function.LongConsumer;
import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

public final class RevokeService implements RevokeUseCase {
    private final JwtTokens jwt;
    private final RefreshTokenStore store;
    private final Clock clock;
    private final LongSupplier nanos;
    private final LongConsumer pause;
    public RevokeService(JwtTokens jwt, RefreshTokenStore store, Clock clock) {
        this(jwt, store, clock, System::nanoTime, millis -> {
            try { Thread.sleep(millis); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AuthFailure(REVOCATION_UNCONFIRMED); }
        });
    }
    public RevokeService(JwtTokens jwt, RefreshTokenStore store, Clock clock, LongSupplier nanos, LongConsumer pause) {
        this.jwt = jwt; this.store = store; this.clock = clock; this.nanos = nanos; this.pause = pause;
    }
    public void revoke(String token) {
        JwtTokens.RefreshClaims claims;
        try { claims = jwt.verifyRefresh(token, true); }
        catch (PortFailure e) { throw new AuthFailure(INVALID_REFRESH_TOKEN); }
        if (!clock.instant().isBefore(claims.expiresAt())) return;
        long started = nanos.getAsLong();
        for (int attempt = 0; attempt < 3; attempt++) {
            long waitMillis = attempt * 100L;
            if (nanos.getAsLong() - started + waitMillis * 1_000_000 > 1_500_000_000L) break;
            if (attempt > 0) pause.accept(waitMillis);
            // Reserve the complete port deadline; never start an attempt that can exceed the total budget.
            if (nanos.getAsLong() - started > 1_500_000_000L) break;
            try { store.delete(claims.jti()); return; }
            catch (PortFailure e) { if (!e.retryable()) break; }
        }
        throw new AuthFailure(REVOCATION_UNCONFIRMED);
    }
}
