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
    final RefreshTokenStore store = mock(RefreshTokenStore.class);
    final RefreshService service = new RefreshService(jwt, store);
    final UUID sid = UUID.randomUUID();
    final UUID user = UUID.randomUUID(), jti = UUID.randomUUID(), newJti = UUID.randomUUID();
    final TokenPair tokens =
            new TokenPair(
                    "at", Instant.now().plusSeconds(900), "rt", Instant.now().plusSeconds(1209600));

    void valid() {
        when(jwt.verifyRefresh("old", false))
                .thenReturn(
                        new JwtTokens.RefreshClaims(
                                user, sid, jti, Instant.now().plusSeconds(300)));
    }

    @Test
    void rejectsInvalidAbsentAndWrongOwnerBeforeIssuing() {
        when(jwt.verifyRefresh("invalid", false))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.INVALID_TOKEN,
                                PortFailure.Execution.NOT_EXECUTED,
                                false));
        failure(() -> service.refresh("invalid"), REFRESH_REJECTED);
        verifyNoInteractions(store);
        valid();
        when(store.consume(jti)).thenReturn(Optional.empty(), Optional.of(UUID.randomUUID()));
        failure(() -> service.refresh("old"), REFRESH_REJECTED);
        failure(() -> service.refresh("old"), REFRESH_REJECTED);
        verify(jwt, never()).issue(any(), any());
    }

    @Test
    void consumptionFailureNeverRetriesOrRestores() {
        valid();
        when(store.consume(jti))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.UNAVAILABLE,
                                PortFailure.Execution.NOT_EXECUTED,
                                true));
        failure(() -> service.refresh("old"), REFRESH_UNAVAILABLE);
        doThrow(new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true))
                .when(store)
                .consume(jti);
        failure(() -> service.refresh("old"), REFRESH_OUTCOME_UNKNOWN);
        verify(store, times(2)).consume(jti);
        verify(store, never()).save(any(), any(), any());
    }

    @Test
    void returnsOnlyAfterSaveAndDoesNotReturnOnSaveFailure() {
        valid();
        when(store.consume(jti)).thenReturn(Optional.of(user));
        when(jwt.issue(user, sid)).thenReturn(new JwtTokens.Issued(tokens, newJti));
        assertThat(service.refresh("old")).isEqualTo(tokens);
        var order = inOrder(jwt, store);
        order.verify(jwt).verifyRefresh("old", false);
        order.verify(store).consume(jti);
        order.verify(jwt).issue(user, sid);
        order.verify(store).save(newJti, user, tokens.refreshExpiresAt());
        doThrow(new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true))
                .when(store)
                .save(newJti, user, tokens.refreshExpiresAt());
        failure(() -> service.refresh("old"), REFRESH_SAVE_FAILED);
        verify(store, times(2)).save(newJti, user, tokens.refreshExpiresAt());
        verify(store, never()).save(eq(jti), any(), any());
    }

    void failure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable task,
            AuthFailure.Reason expected) {
        assertThatThrownBy(task)
                .isInstanceOfSatisfying(
                        AuthFailure.class, e -> assertThat(e.reason()).isEqualTo(expected));
    }
}
