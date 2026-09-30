package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.adapter.out.id.SecureSessionIds;
import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.TermsLoginService;
import com.loresentry.authentication.domain.TermsVersion;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class TermsLoginServiceTest {
    final TermsVersionStore versions = mock(TermsVersionStore.class);
    final TermsAcceptanceStore accepted = mock(TermsAcceptanceStore.class);
    final ConsentRequestStore requests = mock(ConsentRequestStore.class);
    final Instant now = Instant.parse("2026-09-30T00:00:00Z");
    final UUID user = UUID.randomUUID();
    final TermsVersion current =
            new TermsVersion(UUID.randomUUID(), "1", "Terms", "Text", now, now);

    TermsLoginService service(boolean enabled) {
        return new TermsLoginService(
                enabled,
                versions,
                accepted,
                requests,
                new SecureSessionIds(),
                Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void checksCurrentVersionAndCreatesNewPendingCredentialForEachLogin() {
        when(versions.current(now)).thenReturn(Optional.of(current));
        when(requests.create(any(), eq(user), eq(current.id())))
                .thenReturn(
                        Optional.of(
                                new ConsentRequestStore.Pending(
                                        user, current.id(), now, now.plusSeconds(1800))));
        var first = service(true).check(user).orElseThrow();
        var next = service(true).check(user).orElseThrow();
        assertThat(first.id()).isNotEqualTo(next.id());
        assertThat(first.expiresAt()).isEqualTo(now.plusSeconds(1800));
        when(accepted.hasAccepted(user, current.id())).thenReturn(true);
        assertThat(service(true).check(user)).isEmpty();
        verify(requests, times(2)).create(any(), eq(user), eq(current.id()));
    }

    @Test
    void missingTermsAndStoreFailuresNeverPermitLogin() {
        assertThatThrownBy(() -> service(true).check(user))
                .isInstanceOf(AuthFailure.class)
                .hasMessage("LOGIN_UNAVAILABLE");
        when(versions.current(now)).thenReturn(Optional.of(current));
        when(requests.create(any(), any(), any()))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true));
        assertThatThrownBy(() -> service(true).check(user))
                .isInstanceOf(AuthFailure.class)
                .hasMessage("LOGIN_UNAVAILABLE");
        verify(requests, times(1)).create(any(), any(), any());
    }

    @Test
    void rolloutRemainsDisabledUntilApprovedOriginalAndClientsAreReady() {
        assertThat(service(false).check(user)).isEmpty();
        verifyNoInteractions(versions, accepted, requests);
    }
}
