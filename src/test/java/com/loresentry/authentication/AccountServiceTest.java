package com.loresentry.authentication;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.*;
import com.loresentry.authentication.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC);
    private final AccountStore store = mock(AccountStore.class);
    private final UUID id = UUID.randomUUID();

    @Test
    void renamePassesOnlyTrimmedNameAndInjectedTimeToPort() {
        var user = new User(id, "😀".repeat(50), Instant.EPOCH, clock.instant());
        when(store.rename(id, user.displayName(), clock.instant())).thenReturn(Optional.of(new AccountStore.Account(user, new OAuthIdentity("google", "subject", id, null))));
        assertThat(new AccountService(store, clock).rename(id, "  " + user.displayName() + "  ").displayName()).isEqualTo(user.displayName());
        verify(store).rename(id, user.displayName(), clock.instant());
    }

    @Test
    void invalidNameDoesNotReachStoreAndMissingAccountDiffersFromStorageFailure() {
        var service = new AccountService(store, clock);
        for (var name : Arrays.asList(null, "   ", "😀".repeat(51))) {
            assertThatThrownBy(() -> service.rename(id, name)).isInstanceOfSatisfying(AuthFailure.class,
                    failure -> assertThat(failure.reason()).isEqualTo(AuthFailure.Reason.INVALID_DISPLAY_NAME));
        }
        verifyNoInteractions(store);
        when(store.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id)).isInstanceOfSatisfying(AuthFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo(AuthFailure.Reason.USER_NOT_FOUND));
        when(store.findById(id)).thenThrow(new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false));
        assertThatThrownBy(() -> service.get(id)).isInstanceOfSatisfying(AuthFailure.class,
                failure -> assertThat(failure.reason()).isEqualTo(AuthFailure.Reason.ACCOUNT_UNAVAILABLE));
    }

    @Test
    void registrationPreservesSubjectAndUsesUnicodeInitialNameAndClock() {
        when(store.findByIdentity("google", "CaseSensitive")).thenReturn(Optional.empty());
        when(store.create(any(), any())).thenAnswer(call -> new AccountStore.Account(call.getArgument(0), call.getArgument(1)));
        var service = new RegistrationService(store, () -> id, clock);
        var user = service.register(new OidcClient.Identity("google", "CaseSensitive", "😀".repeat(51), null));
        assertThat(user.displayName()).isEqualTo("😀".repeat(50));
        assertThat(user.createdAt()).isEqualTo(clock.instant());
        assertThat(user.updatedAt()).isEqualTo(clock.instant());
        verify(store).create(user, new OAuthIdentity("google", "CaseSensitive", id, null));
        assertThatThrownBy(() -> service.register(new OidcClient.Identity("google", "a".repeat(256), "name", null)))
                .isInstanceOfSatisfying(AuthFailure.class, e -> assertThat(e.reason()).isEqualTo(AuthFailure.Reason.OAUTH_IDENTITY_INVALID));
        assertThatThrownBy(() -> service.register(new OidcClient.Identity("google", "id", "name", "a".repeat(321))))
                .isInstanceOf(AuthFailure.class);
    }

    @Test
    void registrationRecoversDuplicateInNewPortCall() {
        var user = new User(id, "kept", Instant.EPOCH, Instant.EPOCH);
        var account = new AccountStore.Account(user, new OAuthIdentity("google", "id", id, null));
        when(store.findByIdentity("google", "id")).thenReturn(Optional.empty(), Optional.of(account));
        when(store.create(any(), any())).thenThrow(new PortFailure(PortFailure.Kind.IDENTITY_ALREADY_REGISTERED, PortFailure.Execution.UNKNOWN, false));
        var service = new RegistrationService(store, UUID::randomUUID, clock);
        assertThat(service.register(new OidcClient.Identity("google", "id", "new name", null))).isEqualTo(user);
        var order = inOrder(store);
        order.verify(store).findByIdentity("google", "id");
        order.verify(store).create(any(), any());
        order.verify(store).findByIdentity("google", "id");
    }
}
