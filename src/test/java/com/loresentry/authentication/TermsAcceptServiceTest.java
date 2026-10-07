package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.adapter.out.id.SecureSessionIds;
import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.TermsAcceptService;
import com.loresentry.authentication.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;

class TermsAcceptServiceTest {
    final ConsentRequestStore requests = mock(ConsentRequestStore.class);
    final TermsVersionStore versions = mock(TermsVersionStore.class);
    final TermsAcceptanceStore acceptances = mock(TermsAcceptanceStore.class);
    final AccountStore accounts = mock(AccountStore.class);
    final LoginSessionStore sessions = mock(LoginSessionStore.class);
    final Instant now = Instant.parse("2026-09-30T00:00:00Z");
    final UUID user = UUID.randomUUID();
    final UUID version = UUID.randomUUID();
    final ConsentId id = new ConsentId(new SecureSessionIds().generate().value());
    final TermsAcceptService service =
            new TermsAcceptService(
                    requests,
                    versions,
                    acceptances,
                    accounts,
                    new SecureSessionIds(),
                    sessions,
                    Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach
    void setup() {
        when(requests.find(id))
                .thenReturn(
                        Optional.of(
                                new ConsentRequestStore.Pending(
                                        user, version, now, now.plusSeconds(1800))));
        when(requests.consume(id, user, version))
                .thenReturn(ConsentRequestStore.Consumption.CONSUMED);
        when(accounts.findById(user))
                .thenReturn(
                        Optional.of(
                                new AccountStore.Account(
                                        new User(user, "Name", now, now, null, null),
                                        new OAuthIdentity("google", "subject", user, null))));
        when(versions.current(now))
                .thenReturn(
                        Optional.of(new TermsVersion(version, "1", "Terms", "Original", now, now)));
        when(sessions.replace(eq(user), any())).thenReturn(Optional.of(now.plusSeconds(1209600)));
    }

    @Test
    void consumesThenCommitsConsentBeforeIssuingSession() {
        var result = service.accept(id.value(), version.toString());
        assertThat(result.sessionId()).isNotNull();
        var order = inOrder(requests, acceptances, sessions);
        order.verify(requests).find(id);
        order.verify(requests).consume(id, user, version);
        order.verify(acceptances).accept(user, version, now);
        order.verify(sessions).replace(eq(user), any());
    }

    @Test
    void databaseFailureNeverIssuesSessionOrRestoresConsumedRequest() {
        doThrow(unavailable()).when(acceptances).accept(user, version, now);
        assertThatThrownBy(() -> service.accept(id.value(), version.toString()))
                .isInstanceOf(AuthFailure.class)
                .hasMessage("LOGIN_UNAVAILABLE");
        verifyNoInteractions(sessions);
        verify(requests, never()).create(any(), any(), any());
        verify(acceptances, times(1)).accept(user, version, now);
    }

    @Test
    void lostSessionResponsePreservesCommittedAcceptanceWithoutRetry() {
        when(sessions.replace(any(), any())).thenThrow(unavailable());
        assertThatThrownBy(() -> service.accept(id.value(), version.toString()))
                .isInstanceOf(AuthFailure.class)
                .hasMessage("LOGIN_UNAVAILABLE");
        verify(acceptances, times(1)).accept(user, version, now);
        verify(sessions, times(1)).replace(any(), any());
        verify(requests, never()).create(any(), any(), any());
    }

    @Test
    void malformedInputAndVersionRacesNeverSaveConsent() {
        assertThatThrownBy(() -> service.accept(id.value(), "1-1-1-1-1"))
                .hasMessage("INVALID_REQUEST");
        assertThatThrownBy(() -> service.accept("bad", version.toString()))
                .hasMessage("CONSENT_REQUEST_INVALID");
        when(requests.consume(id, user, version))
                .thenReturn(ConsentRequestStore.Consumption.VERSION_MISMATCH);
        assertThatThrownBy(() -> service.accept(id.value(), version.toString()))
                .hasMessage("TERMS_VERSION_MISMATCH");
        verifyNoInteractions(acceptances, sessions);
    }

    @Test
    void accountDeletedDuringIssuanceRevokesTheNewSession() {
        when(accounts.findById(user))
                .thenReturn(
                        Optional.of(
                                new AccountStore.Account(
                                        new User(user, "Name", now, now, null, null),
                                        new OAuthIdentity("google", "subject", user, null))),
                        Optional.empty());
        assertThatThrownBy(() -> service.accept(id.value(), version.toString()))
                .hasMessage("LOGIN_UNAVAILABLE");
        var issued = ArgumentCaptor.forClass(SessionId.class);
        verify(sessions).replace(eq(user), issued.capture());
        verify(sessions).revoke(issued.getValue());
    }

    @Test
    void consentOfADeletedAccountIsInvalidWithoutConsumingIt() {
        when(accounts.findById(user)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.accept(id.value(), version.toString()))
                .hasMessage("CONSENT_REQUEST_INVALID");
        verify(requests, never()).consume(any(), any(), any());
        verifyNoInteractions(acceptances, sessions);
    }

    private PortFailure unavailable() {
        return new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true);
    }
}
