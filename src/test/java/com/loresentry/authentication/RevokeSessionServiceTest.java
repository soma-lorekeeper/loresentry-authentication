package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.RevokeSessionService;
import com.loresentry.authentication.domain.SessionId;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RevokeSessionServiceTest {
    final LoginSessionStore store = mock(LoginSessionStore.class);
    final SessionId id = new SessionId("A".repeat(43));
    final AtomicLong elapsed = new AtomicLong();
    final List<Long> waits = new ArrayList<>();

    RevokeSessionService service() {
        return new RevokeSessionService(
                store,
                elapsed::get,
                millis -> {
                    waits.add(millis);
                    elapsed.addAndGet(millis * 1_000_000);
                });
    }

    PortFailure temporary() {
        return new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true);
    }

    @Test
    void retriesTransientOnlyThreeTimesWithSpecifiedWaits() {
        doAnswer(
                        call -> {
                            elapsed.addAndGet(500_000_000);
                            throw temporary();
                        })
                .when(store)
                .revoke(id);
        assertThatThrownBy(() -> service().revoke(id.value()))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(AuthFailure.Reason.REVOCATION_UNCONFIRMED));
        verify(store, times(3)).revoke(id);
        assertThat(waits).containsExactly(100L, 200L);
        assertThat(elapsed.get()).isEqualTo(1_800_000_000);
    }

    @Test
    void stopsBeforeStartingAnAttemptThatCannotFitTheDeadline() {
        doAnswer(
                        call -> {
                            elapsed.addAndGet(1_500_000_000);
                            throw temporary();
                        })
                .when(store)
                .revoke(id);
        assertThatThrownBy(() -> service().revoke(id.value())).isInstanceOf(AuthFailure.class);
        verify(store, times(1)).revoke(id);
        assertThat(elapsed.get()).isLessThanOrEqualTo(2_000_000_000L);
    }

    @Test
    void successAfterTimeoutAndNonTransientFailureHaveDifferentRetryRules() {
        doThrow(temporary()).doNothing().when(store).revoke(id);
        service().revoke(id.value());
        verify(store, times(2)).revoke(id);
        reset(store);
        waits.clear();
        doThrow(
                        new PortFailure(
                                PortFailure.Kind.UNAVAILABLE,
                                PortFailure.Execution.NOT_EXECUTED,
                                false))
                .when(store)
                .revoke(id);
        assertThatThrownBy(() -> service().revoke(id.value())).isInstanceOf(AuthFailure.class);
        verify(store, times(1)).revoke(id);
        assertThat(waits).isEmpty();
    }

    @Test
    void malformedIdsNeverReachTheStoreAndCorruptRecordsAreUnconfirmed() {
        for (var value : List.of("", "wrong", "a".repeat(64), "A".repeat(42) + "B")) {
            assertThatThrownBy(() -> service().revoke(value))
                    .isInstanceOfSatisfying(
                            AuthFailure.class,
                            e ->
                                    assertThat(e.reason())
                                            .isEqualTo(AuthFailure.Reason.INVALID_SESSION_ID));
        }
        verifyNoInteractions(store);
        doThrow(
                        new PortFailure(
                                PortFailure.Kind.INVALID_DATA,
                                PortFailure.Execution.NOT_EXECUTED,
                                false))
                .when(store)
                .revoke(id);
        assertThatThrownBy(() -> service().revoke(id.value()))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(AuthFailure.Reason.REVOCATION_UNCONFIRMED));
        verify(store, times(1)).revoke(id);
    }
}
