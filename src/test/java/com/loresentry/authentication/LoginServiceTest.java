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
    final JwtTokens jwt = mock(JwtTokens.class);
    final SessionStore refresh = mock(SessionStore.class);
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
    final TokenPair tokens =
            new TokenPair("at", now.plusSeconds(900), "rt", now.plusSeconds(1209600));
    final UUID jti = UUID.randomUUID();

    LoginService service() {
        when(provider.settings())
                .thenReturn(new OidcClient.Settings("google", "client", "https://callback"));
        when(states.find(id)).thenReturn(Optional.of(state));
        when(states.consume(id)).thenReturn(Optional.of(state));
        when(provider.exchange("code", state)).thenReturn(identity);
        when(accounts.register(identity)).thenReturn(user);
        when(jwt.issue(eq(user.id()), any(UUID.class)))
                .thenReturn(new JwtTokens.Issued(tokens, jti));
        return new LoginService(
                new OAuthRequests(states, provider, Clock.fixed(now, ZoneOffset.UTC), random),
                provider,
                accounts,
                jwt,
                refresh);
    }

    LoginUseCase.Callback command() {
        return new LoginUseCase.Callback(id, state.state(), "code", null);
    }

    @Test
    void returnsOnlyAfterVerifiedIdentityCommittedAccountAndSavedRefreshState() {
        var service = service();
        assertThat(service.callback(command()))
                .isEqualTo(new LoginUseCase.LoginResult(tokens, AuthFailure.Consumption.CONSUMED));
        var order = inOrder(states, provider, accounts, jwt, refresh);
        order.verify(provider).settings();
        order.verify(states).find(id);
        order.verify(states).consume(id);
        order.verify(provider).exchange("code", state);
        order.verify(accounts).register(identity);
        var sid = org.mockito.ArgumentCaptor.forClass(UUID.class);
        order.verify(jwt).issue(eq(user.id()), sid.capture());
        order.verify(refresh)
                .replace(
                        user.id(),
                        new SessionStore.Session(sid.getValue(), jti, tokens.refreshExpiresAt()));
    }

    @Test
    void signingFailureDoesNotReplaceExistingSession() {
        var service = service();
        doThrow(new IllegalStateException("signing failed")).when(jwt).issue(any(), any());
        failure(
                () -> service.callback(command()),
                AuthFailure.Reason.INTERNAL_ERROR,
                AuthFailure.Consumption.CONSUMED);
        verifyNoInteractions(refresh);
    }

    @Test
    void everySuccessfulLoginCreatesANewSessionId() {
        var service = service();
        service.callback(command());
        service.callback(command());
        var sessions = org.mockito.ArgumentCaptor.forClass(SessionStore.Session.class);
        verify(refresh, times(2)).replace(eq(user.id()), sessions.capture());
        assertThat(sessions.getAllValues().get(0).sid())
                .isNotEqualTo(sessions.getAllValues().get(1).sid());
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
        verifyNoInteractions(accounts, jwt, refresh);
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
        verifyNoInteractions(accounts, jwt, refresh);
        doReturn(identity).when(provider).exchange("code", state);
        doThrow(new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE))
                .when(accounts)
                .register(identity);
        failure(
                () -> service.callback(command()),
                AuthFailure.Reason.LOGIN_UNAVAILABLE,
                AuthFailure.Consumption.CONSUMED);
        verifyNoInteractions(jwt, refresh);
        doReturn(user).when(accounts).register(identity);
        doThrow(new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true))
                .when(refresh)
                .replace(eq(user.id()), any(SessionStore.Session.class));
        failure(
                () -> service.callback(command()),
                AuthFailure.Reason.LOGIN_UNAVAILABLE,
                AuthFailure.Consumption.CONSUMED);
        verify(refresh, times(1)).replace(eq(user.id()), any(SessionStore.Session.class));
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
        verifyNoInteractions(accounts, jwt, refresh);
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
