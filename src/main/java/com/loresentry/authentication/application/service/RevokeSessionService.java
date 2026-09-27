package com.loresentry.authentication.application.service;

import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.SessionId;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/** Revoke only the supplied ID within a two-second budget, including retries. */
public final class RevokeSessionService implements RevokeSessionUseCase {
    private final LoginSessionStore store;
    private final LongSupplier nanos;
    private final LongConsumer pause;

    public RevokeSessionService(LoginSessionStore store) {
        this(
                store,
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

    public RevokeSessionService(LoginSessionStore store, LongSupplier nanos, LongConsumer pause) {
        this.store = store;
        this.nanos = nanos;
        this.pause = pause;
    }

    public void revoke(String value) {
        SessionId id;
        try {
            id = new SessionId(value);
        } catch (IllegalArgumentException e) {
            throw new AuthFailure(INVALID_SESSION_ID);
        }
        long started = nanos.getAsLong();
        for (int attempt = 0; attempt < 3; attempt++) {
            long waitMillis = attempt * 100L;
            if (nanos.getAsLong() - started + waitMillis * 1_000_000 > 1_500_000_000L) break;
            if (attempt > 0) pause.accept(waitMillis);
            // Reserve the complete port deadline; never start an attempt that can exceed the total
            // budget.
            if (nanos.getAsLong() - started > 1_500_000_000L) break;
            try {
                store.revoke(id);
                return;
            } catch (PortFailure e) {
                if (e.kind() == PortFailure.Kind.INVALID_DATA)
                    throw new AuthFailure(REVOCATION_UNCONFIRMED);
                if (!e.retryable()) break;
            }
        }
        throw new AuthFailure(REVOCATION_UNCONFIRMED);
    }
}
