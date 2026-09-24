package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.*;
import com.loresentry.authentication.support.DatabaseTestSupport;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class LoginCommitTest extends DatabaseTestSupport {
    @Autowired OAuthStateStore states;
    @Autowired RegisterIdentityUseCase accounts;
    @Autowired AccountStore accountStore;
    @Autowired JwtTokens jwt;
    @Autowired SessionStore refresh;

    @Test
    void committedAccountSurvivesRefreshSaveFailureAndReloginUsesSameUuid() {
        var provider = mock(OidcClient.class);
        var broken = mock(SessionStore.class);
        var identity =
                new OidcClient.Identity(
                        "google", "commit-" + UUID.randomUUID(), "Name", "same@example.com");
        when(provider.settings())
                .thenReturn(new OidcClient.Settings("google", "client", "https://callback"));
        when(provider.authorizationUrl(any()))
                .thenReturn(URI.create("https://accounts.google.com/authorize"));
        when(provider.exchange(eq("code"), any())).thenReturn(identity);
        var requests = new OAuthRequests(states, provider, Clock.systemUTC(), new SecureRandom());
        var login = new LoginService(requests, provider, accounts, jwt, broken);
        doAnswer(
                        call -> {
                            assertThat(accountStore.findByIdentity("google", identity.subject()))
                                    .isPresent();
                            throw new PortFailure(
                                    PortFailure.Kind.UNAVAILABLE,
                                    PortFailure.Execution.UNKNOWN,
                                    true);
                        })
                .when(broken)
                .replace(any(), any());
        var prepared = login.prepare();
        var state = states.find(prepared.loginRequestId()).orElseThrow();
        assertThatThrownBy(
                        () ->
                                login.callback(
                                        new LoginUseCase.Callback(
                                                prepared.loginRequestId(),
                                                state.state(),
                                                "code",
                                                null)))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        e -> {
                            assertThat(e.reason()).isEqualTo(AuthFailure.Reason.LOGIN_UNAVAILABLE);
                            assertThat(e.consumption()).isEqualTo(AuthFailure.Consumption.CONSUMED);
                        });
        UUID committed =
                accountStore.findByIdentity("google", identity.subject()).orElseThrow().user().id();
        assertThat(states.find(prepared.loginRequestId())).isEmpty();
        var retry = new LoginService(requests, provider, accounts, jwt, refresh);
        var next = retry.prepare();
        var nextState = states.find(next.loginRequestId()).orElseThrow();
        var result =
                retry.callback(
                        new LoginUseCase.Callback(
                                next.loginRequestId(), nextState.state(), "code", null));
        assertThat(jwt.verifyRefresh(result.tokens().refreshToken(), false).userId())
                .isEqualTo(committed);
    }
}
