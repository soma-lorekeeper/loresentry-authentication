package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.RevokeService;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RevokeServiceTest {
    final JwtTokens jwt = mock(JwtTokens.class);
    final RefreshTokenStore store = mock(RefreshTokenStore.class);
    final Instant now = Instant.parse("2026-09-17T00:00:00Z");
    final UUID jti = UUID.randomUUID();
    final AtomicLong elapsed = new AtomicLong();
    final List<Long> waits = new ArrayList<>();

    RevokeService service() {
        return new RevokeService(
                jwt,
                store,
                Clock.fixed(now, ZoneOffset.UTC),
                elapsed::get,
                millis -> {
                    waits.add(millis);
                    elapsed.addAndGet(millis * 1_000_000);
                });
    }

    void valid(Instant exp) {
        when(jwt.verifyRefresh("rt", true))
                .thenReturn(
                        new JwtTokens.RefreshClaims(
                                UUID.randomUUID(), UUID.randomUUID(), jti, exp));
    }

    PortFailure temporary() {
        return new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true);
    }

    @Test
    void retriesTransientOnlyThreeTimesWithSpecifiedWaits() {
        valid(now.plusSeconds(300));
        doAnswer(
                        call -> {
                            elapsed.addAndGet(500_000_000);
                            throw temporary();
                        })
                .when(store)
                .delete(jti);
        assertThatThrownBy(() -> service().revoke("rt"))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(AuthFailure.Reason.REVOCATION_UNCONFIRMED));
        verify(store, times(3)).delete(jti);
        assertThat(waits).containsExactly(100L, 200L);
        assertThat(elapsed.get()).isEqualTo(1_800_000_000);
    }

    @Test
    void stopsBeforeStartingAnAttemptThatCannotFitTheDeadline() {
        valid(now.plusSeconds(300));
        doAnswer(
                        call -> {
                            elapsed.addAndGet(1_500_000_000);
                            throw temporary();
                        })
                .when(store)
                .delete(jti);
        assertThatThrownBy(() -> service().revoke("rt")).isInstanceOf(AuthFailure.class);
        verify(store, times(1)).delete(jti);
        assertThat(elapsed.get()).isLessThanOrEqualTo(2_000_000_000L);
    }

    @Test
    void successAfterTimeoutAndNonTransientFailureHaveDifferentRetryRules() {
        valid(now.plusSeconds(300));
        doThrow(temporary()).doNothing().when(store).delete(jti);
        service().revoke("rt");
        verify(store, times(2)).delete(jti);
        reset(store);
        waits.clear();
        doThrow(
                        new PortFailure(
                                PortFailure.Kind.UNAVAILABLE,
                                PortFailure.Execution.NOT_EXECUTED,
                                false))
                .when(store)
                .delete(jti);
        assertThatThrownBy(() -> service().revoke("rt")).isInstanceOf(AuthFailure.class);
        verify(store, times(1)).delete(jti);
        assertThat(waits).isEmpty();
    }

    @Test
    void expiredValidTokenIsSuccessButInvalidSignatureStillFails() {
        valid(now);
        service().revoke("rt");
        verifyNoInteractions(store);
        when(jwt.verifyRefresh("forged", true))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.INVALID_TOKEN,
                                PortFailure.Execution.NOT_EXECUTED,
                                false));
        assertThatThrownBy(() -> service().revoke("forged"))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(AuthFailure.Reason.INVALID_REFRESH_TOKEN));
        verifyNoInteractions(store);
    }
}
