package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.*;
import com.loresentry.authentication.domain.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LoginServiceTest {
    final OAuthStateStore states = mock(OAuthStateStore.class);
    final OidcClient provider = mock(OidcClient.class);
    final RegisterIdentityUseCase accounts = mock(RegisterIdentityUseCase.class);
    final SessionIdGenerator ids = mock(SessionIdGenerator.class);
    final LoginSessionStore sessions = mock(LoginSessionStore.class);
    final Instant now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
    final SecureRandom random = new SecureRandom();
    final String id = OAuthSecrets.generate(random);
    final OAuthStateStore.State state =
            new OAuthStateStore.State(
                    1,
                    "google",
                    "client",
                    "https://callback",
                    OAuthSecrets.generate(random),
                    OAuthSecrets.generate(random),
                    OAuthSecrets.generate(random),
                    now,
                    now.plusSeconds(300));
    final OidcClient.Identity identity = new OidcClient.Identity("google", "subject", "Name", null);
    final User user = new User(UUID.randomUUID(), "Name", now, now);
    final SessionId sessionId = new SessionId("A".repeat(43));
    final Instant expiry = now.plusSeconds(1209600);

    LoginService service() {
        when(provider.settings())
                .thenReturn(new OidcClient.Settings("google", "client", "https://callback"));
        when(states.find(id)).thenReturn(Optional.of(state));
        when(states.consume(id)).thenReturn(Optional.of(state));
        when(provider.exchange("code", state)).thenReturn(identity);
        when(accounts.register(identity)).thenReturn(user);
        when(ids.generate()).thenReturn(sessionId);
        when(sessions.replace(user.id(), sessionId)).thenReturn(Optional.of(expiry));
        return new LoginService(
                new OAuthRequests(states, provider, Clock.fixed(now, ZoneOffset.UTC), random),
                provider,
                accounts,
                ids,
                sessions);
    }

    LoginUseCase.Callback command() {
        return new LoginUseCase.Callback(id, state.state(), "code", null);
    }

    @Test
    void returnsOnlyAfterVerifiedIdentityCommittedAccountAndSavedSession() {
        var service = service();
        assertThat(service.callback(command()))
                .isEqualTo(
                        new LoginUseCase.LoginResult(
                                sessionId, expiry, AuthFailure.Consumption.CONSUMED));
        var order = inOrder(states, provider, accounts, ids, sessions);
        order.verify(provider).settings();
        order.verify(states).find(id);
        order.verify(states).consume(id);
        order.verify(provider).exchange("code", state);
        order.verify(accounts).register(identity);
        order.verify(ids).generate();
        order.verify(sessions).replace(user.id(), sessionId);
    }

    @Test
    void generatorFailureDoesNotReplaceExistingSession() {
        var service = service();
        doThrow(new IllegalStateException("generator failed")).when(ids).generate();
        failure(
                () -> service.callback(command()),
                AuthFailure.Reason.INTERNAL_ERROR,
                AuthFailure.Consumption.CONSUMED);
        verifyNoInteractions(sessions);
    }

    @Test
    void confirmedCollisionGeneratesAnotherIdButNeverRetriesUnknownWrites() {
        var service = service();
        var second = new SessionId("B".repeat(42) + "A");
        when(ids.generate()).thenReturn(sessionId, second);
        when(sessions.replace(user.id(), sessionId)).thenReturn(Optional.empty());
        when(sessions.replace(user.id(), second)).thenReturn(Optional.of(expiry));
        assertThat(service.callback(command()).sessionId()).isEqualTo(second);
        verify(sessions).replace(user.id(), sessionId);
        verify(sessions).replace(user.id(), second);
    }

    @Test
    void cancellationConsumesButNeverExchangesCode() {
        var service = service();
        failure(
                () ->
                        service.callback(
                                new LoginUseCase.Callback(
                                        id, state.state(), null, "access_denied")),
                AuthFailure.Reason.OAUTH_LOGIN_DENIED,
                AuthFailure.Consumption.CONSUMED);
        verify(provider, never()).exchange(any(), any());
        verifyNoInteractions(accounts, ids, sessions);
    }

    @Test
    void identityAndAccountAndSaveFailuresStopAtTheirOwnStage() {
        var service = service();
        doThrow(
                        new PortFailure(
                                PortFailure.Kind.INVALID_IDENTITY,
                                PortFailure.Execution.UNKNOWN,
                                false))
                .when(provider)
                .exchange("code", state);
        failure(
                () -> service.callback(command()),
                AuthFailure.Reason.OAUTH_IDENTITY_INVALID,
                AuthFailure.Consumption.CONSUMED);
        verifyNoInteractions(accounts, ids, sessions);
        doReturn(identity).when(provider).exchange("code", state);
        doThrow(new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE))
                .when(accounts)
                .register(identity);
        failure(
                () -> service.callback(command()),
                AuthFailure.Reason.LOGIN_UNAVAILABLE,
                AuthFailure.Consumption.CONSUMED);
        verifyNoInteractions(ids, sessions);
        doReturn(user).when(accounts).register(identity);
        doThrow(new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true))
                .when(sessions)
                .replace(eq(user.id()), any(SessionId.class));
        failure(
                () -> service.callback(command()),
                AuthFailure.Reason.LOGIN_UNAVAILABLE,
                AuthFailure.Consumption.CONSUMED);
        verify(sessions, times(1)).replace(eq(user.id()), any(SessionId.class));
    }

    @Test
    void invalidInputsAndUnknownConsumptionNeverReachProvider() {
        var service = service();
        failure(
                () ->
                        service.callback(
                                new LoginUseCase.Callback(id, state.state(), "code", "denied")),
                AuthFailure.Reason.OAUTH_REQUEST_INVALID,
                AuthFailure.Consumption.NOT_CONSUMED);
        verify(states, never()).consume(any());
        doThrow(new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true))
                .when(states)
                .consume(id);
        failure(
                () -> service.callback(command()),
                AuthFailure.Reason.LOGIN_UNAVAILABLE,
                AuthFailure.Consumption.UNKNOWN);
        verify(provider, never()).exchange(any(), any());
        verifyNoInteractions(accounts, ids, sessions);
    }

    void failure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
            AuthFailure.Reason reason,
            AuthFailure.Consumption consumption) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        e -> {
                            assertThat(e.reason()).isEqualTo(reason);
                            assertThat(e.consumption()).isEqualTo(consumption);
                        });
    }
}
