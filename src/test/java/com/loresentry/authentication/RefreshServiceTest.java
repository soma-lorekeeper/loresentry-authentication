package com.loresentry.authentication;

import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.RefreshService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RefreshServiceTest {
    final JwtTokens jwt = mock(JwtTokens.class);
    final SessionStore store = mock(SessionStore.class);
    final RefreshService service = new RefreshService(jwt, store);
    final UUID sid = UUID.randomUUID();
    final UUID user = UUID.randomUUID(), jti = UUID.randomUUID(), newJti = UUID.randomUUID();
    final Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    final TokenPair tokens =
            new TokenPair("at", now.plusSeconds(900), "rt", now.plusSeconds(1209600));
    final SessionStore.Session expected = new SessionStore.Session(sid, jti, now.plusSeconds(300));
    final SessionStore.Session replacement =
            new SessionStore.Session(sid, newJti, tokens.refreshExpiresAt());

    void valid() {
        when(jwt.verifyRefresh("old", false))
                .thenReturn(
                        new JwtTokens.RefreshClaims(user, sid, jti, expected.refreshExpiresAt()));
        when(jwt.issue(user, sid)).thenReturn(new JwtTokens.Issued(tokens, newJti));
    }

    @Test
    void invalidTokenNeverReachesSigningOrStorage() {
        when(jwt.verifyRefresh("invalid", false))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.INVALID_TOKEN,
                                PortFailure.Execution.NOT_EXECUTED,
                                false));
        failure(() -> service.refresh("invalid"), REFRESH_REJECTED);
        verify(jwt, never()).issue(any(), any());
        verifyNoInteractions(store);
    }

    @Test
    void rejectedComparisonDoesNotReturnTokensOrAttemptRecovery() {
        valid();
        when(store.rotate(user, expected, replacement)).thenReturn(false);
        failure(() -> service.refresh("old"), REFRESH_REJECTED);
        verify(store).rotate(user, expected, replacement);
        verifyNoMoreInteractions(store);
    }

    @Test
    void distinguishesNotExecutedUnknownAndCorruptDataWithoutRetry() {
        valid();
        when(store.rotate(user, expected, replacement))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.UNAVAILABLE,
                                PortFailure.Execution.NOT_EXECUTED,
                                true))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.INVALID_DATA,
                                PortFailure.Execution.NOT_EXECUTED,
                                false));
        failure(() -> service.refresh("old"), REFRESH_UNAVAILABLE);
        failure(() -> service.refresh("old"), REFRESH_OUTCOME_UNKNOWN);
        failure(() -> service.refresh("old"), INTERNAL_ERROR);
        verify(store, times(3)).rotate(user, expected, replacement);
        verifyNoMoreInteractions(store);
    }

    @Test
    void signsBeforeAtomicRotationAndReturnsOnlyAfterSuccess() {
        valid();
        when(store.rotate(user, expected, replacement)).thenReturn(true);
        assertThat(service.refresh("old")).isEqualTo(tokens);
        var order = inOrder(jwt, store);
        order.verify(jwt).verifyRefresh("old", false);
        order.verify(jwt).issue(user, sid);
        order.verify(store).rotate(user, expected, replacement);
        order.verifyNoMoreInteractions();
    }

    @Test
    void signingFailurePreservesTheCurrentSession() {
        valid();
        when(jwt.issue(user, sid)).thenThrow(new IllegalStateException("private signing detail"));
        failure(() -> service.refresh("old"), INTERNAL_ERROR);
        verifyNoInteractions(store);
    }

    void failure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable task,
            AuthFailure.Reason expected) {
        assertThatThrownBy(task)
                .isInstanceOfSatisfying(
                        AuthFailure.class, e -> assertThat(e.reason()).isEqualTo(expected));
    }
}
