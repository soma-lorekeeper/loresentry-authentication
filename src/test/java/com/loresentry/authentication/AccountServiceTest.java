package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.*;
import com.loresentry.authentication.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AccountServiceTest {
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC);
    private final AccountStore store = mock(AccountStore.class);
    private final LoginSessionStore sessions = mock(LoginSessionStore.class);
    private final UUID id = UUID.randomUUID();

    @Test
    void renamePassesOnlyTrimmedNameAndInjectedTimeToPort() {
        var user = new User(id, "😀".repeat(50), Instant.EPOCH, clock.instant(), null, null);
        when(store.rename(id, user.displayName(), clock.instant()))
                .thenReturn(
                        Optional.of(
                                new AccountStore.Account(
                                        user, new OAuthIdentity("google", "subject", id, null))));
        assertThat(
                        new AccountService(store, sessions, clock)
                                .rename(id, "  " + user.displayName() + "  ")
                                .displayName())
                .isEqualTo(user.displayName());
        verify(store).rename(id, user.displayName(), clock.instant());
    }

    @Test
    void invalidNameDoesNotReachStoreAndMissingAccountDiffersFromStorageFailure() {
        var service = new AccountService(store, sessions, clock);
        for (var name : Arrays.asList(null, "   ", "😀".repeat(51))) {
            assertThatThrownBy(() -> service.rename(id, name))
                    .isInstanceOfSatisfying(
                            AuthFailure.class,
                            failure ->
                                    assertThat(failure.reason())
                                            .isEqualTo(AuthFailure.Reason.INVALID_DISPLAY_NAME));
        }
        verifyNoInteractions(store);
        when(store.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(AuthFailure.Reason.USER_NOT_FOUND));
        when(store.findById(id))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.UNAVAILABLE,
                                PortFailure.Execution.UNKNOWN,
                                false));
        assertThatThrownBy(() -> service.get(id))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(AuthFailure.Reason.ACCOUNT_UNAVAILABLE));
    }

    @Test
    void registrationPreservesSubjectAndUsesUnicodeInitialNameAndClock() {
        when(store.findByIdentity("google", "CaseSensitive")).thenReturn(Optional.empty());
        when(store.create(any(), any()))
                .thenAnswer(
                        call -> new AccountStore.Account(call.getArgument(0), call.getArgument(1)));
        var service = new RegistrationService(store, () -> id, clock);
        var user =
                service.register(
                        new OidcClient.Identity("google", "CaseSensitive", "😀".repeat(51), null));
        assertThat(user.displayName()).isEqualTo("😀".repeat(50));
        assertThat(user.createdAt()).isEqualTo(clock.instant());
        assertThat(user.updatedAt()).isEqualTo(clock.instant());
        verify(store).create(user, new OAuthIdentity("google", "CaseSensitive", id, null));
        assertThatThrownBy(
                        () ->
                                service.register(
                                        new OidcClient.Identity(
                                                "google", "a".repeat(256), "name", null)))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(AuthFailure.Reason.OAUTH_IDENTITY_INVALID));
        assertThatThrownBy(
                        () ->
                                service.register(
                                        new OidcClient.Identity(
                                                "google", "id", "name", "a".repeat(321))))
                .isInstanceOf(AuthFailure.class);
    }

    @Test
    void registrationFallsBackToTheEmailLocalPartAndThenToWriter() {
        when(store.findByIdentity(eq("google"), any())).thenReturn(Optional.empty());
        when(store.create(any(), any()))
                .thenAnswer(
                        call -> new AccountStore.Account(call.getArgument(0), call.getArgument(1)));
        var service = new RegistrationService(store, UUID::randomUUID, clock);
        var cases =
                new String[][] {
                    {"  Google Name  ", "writer@example.test", "Google Name"},
                    {null, "  pen.name+tag@example.test", "pen.name+tag"},
                    {"   ", "writer@example.test", "writer"},
                    {null, "😀".repeat(51) + "@example.test", "😀".repeat(50)},
                    {null, "@example.test", "Writer"},
                    {null, "   @example.test", "Writer"},
                    {null, "no-at-sign", "Writer"},
                    {"", "  ", "Writer"},
                    {null, null, "Writer"}
                };
        for (var value : cases) {
            var user =
                    service.register(
                            new OidcClient.Identity(
                                    "google", UUID.randomUUID().toString(), value[0], value[1]));
            assertThat(user.displayName()).isEqualTo(value[2]);
            assertThat(user.locale()).isNull();
        }
    }

    @Test
    void changeLocalePassesTheSupportedCodeAndInjectedTimeToPort() {
        var user = new User(id, "Name", Instant.EPOCH, clock.instant(), null, SupportedLocale.EN);
        when(store.changeLocale(id, SupportedLocale.EN, clock.instant()))
                .thenReturn(
                        Optional.of(
                                new AccountStore.Account(
                                        user, new OAuthIdentity("google", "subject", id, null))));
        assertThat(new AccountService(store, sessions, clock).changeLocale(id, "en"))
                .isEqualTo(new AccountUseCase.Profile(id, "Name", null, false, SupportedLocale.EN));
        verify(store).changeLocale(id, SupportedLocale.EN, clock.instant());
    }

    @Test
    void unsupportedLocaleIsAnInvalidRequestThatNeverReachesTheStore() {
        var service = new AccountService(store, sessions, clock);
        for (var locale : Arrays.asList(null, "", "EN", "Ko", " en", "en ", "en-US", "ja"))
            assertReason(
                    () -> service.changeLocale(id, locale), AuthFailure.Reason.INVALID_REQUEST);
        assertReason(
                () -> service.changeLocale(null, "en"), AuthFailure.Reason.USER_CONTEXT_REQUIRED);
        verifyNoInteractions(store);
    }

    @Test
    void changeLocaleSeparatesMissingAccountFromStorageFailure() {
        var service = new AccountService(store, sessions, clock);
        when(store.changeLocale(id, SupportedLocale.KO, clock.instant()))
                .thenReturn(Optional.empty());
        assertReason(() -> service.changeLocale(id, "ko"), AuthFailure.Reason.USER_NOT_FOUND);
        when(store.changeLocale(id, SupportedLocale.KO, clock.instant())).thenThrow(unavailable());
        assertReason(() -> service.changeLocale(id, "ko"), AuthFailure.Reason.ACCOUNT_UNAVAILABLE);
    }

    @Test
    void registrationRecoversDuplicateInNewPortCall() {
        var user = new User(id, "kept", Instant.EPOCH, Instant.EPOCH, null, null);
        var account = new AccountStore.Account(user, new OAuthIdentity("google", "id", id, null));
        when(store.findByIdentity("google", "id"))
                .thenReturn(Optional.empty(), Optional.of(account));
        when(store.create(any(), any()))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.IDENTITY_ALREADY_REGISTERED,
                                PortFailure.Execution.UNKNOWN,
                                false));
        var service = new RegistrationService(store, UUID::randomUUID, clock);
        assertThat(service.register(new OidcClient.Identity("google", "id", "new name", null)))
                .isEqualTo(user);
        var order = inOrder(store);
        order.verify(store).findByIdentity("google", "id");
        order.verify(store).create(any(), any());
        order.verify(store).findByIdentity("google", "id");
    }

    @Test
    void onboardingUsesInjectedTimeAndSeparatesMissingAccountFromStorageFailure() {
        var service = new AccountService(store, sessions, clock);
        when(store.completeOnboarding(id, clock.instant())).thenReturn(true, false);
        service.completeOnboarding(id);
        verify(store).completeOnboarding(id, clock.instant());
        assertReason(() -> service.completeOnboarding(id), AuthFailure.Reason.USER_NOT_FOUND);
        when(store.completeOnboarding(id, clock.instant())).thenThrow(unavailable());
        assertReason(() -> service.completeOnboarding(id), AuthFailure.Reason.ACCOUNT_UNAVAILABLE);
        assertReason(
                () -> service.completeOnboarding(null), AuthFailure.Reason.USER_CONTEXT_REQUIRED);
    }

    @Test
    void profileReportsOnboardingCompletionFromTheStoredTime() {
        var service = new AccountService(store, sessions, clock);
        var identity = new OAuthIdentity("google", "subject", id, null);
        when(store.findById(id))
                .thenReturn(
                        Optional.of(
                                new AccountStore.Account(
                                        new User(
                                                id,
                                                "Name",
                                                Instant.EPOCH,
                                                Instant.EPOCH,
                                                null,
                                                null),
                                        identity)),
                        Optional.of(
                                new AccountStore.Account(
                                        new User(
                                                id,
                                                "Name",
                                                Instant.EPOCH,
                                                Instant.EPOCH,
                                                Instant.EPOCH,
                                                null),
                                        identity)));
        assertThat(service.get(id).onboardingCompleted()).isFalse();
        assertThat(service.get(id).onboardingCompleted()).isTrue();
    }

    @Test
    void deletionRevokesSessionsBeforeAndAfterCommittingTheDelete() {
        when(store.delete(id)).thenReturn(true);
        new AccountService(store, sessions, clock).delete(id);
        var order = inOrder(sessions, store);
        order.verify(sessions).revokeUser(id);
        order.verify(store).delete(id);
        order.verify(sessions).revokeUser(id);
    }

    @Test
    void unconfirmedRevocationKeepsTheAccount() {
        doThrow(unavailable()).when(sessions).revokeUser(id);
        assertReason(
                () -> new AccountService(store, sessions, clock).delete(id),
                AuthFailure.Reason.ACCOUNT_UNAVAILABLE);
        verify(store, never()).delete(any());
    }

    @Test
    void deletionFailuresAndMissingAccountsKeepTheirReasons() {
        var service = new AccountService(store, sessions, clock);
        when(store.delete(id)).thenReturn(false);
        assertReason(() -> service.delete(id), AuthFailure.Reason.USER_NOT_FOUND);
        verify(sessions, times(1)).revokeUser(id);
        when(store.delete(id)).thenThrow(unavailable());
        assertReason(() -> service.delete(id), AuthFailure.Reason.ACCOUNT_UNAVAILABLE);
        assertReason(() -> service.delete(null), AuthFailure.Reason.USER_CONTEXT_REQUIRED);
    }

    @Test
    void committedDeletionSucceedsWhenTheFollowUpRevocationFails() {
        when(store.delete(id)).thenReturn(true);
        doNothing().doThrow(unavailable()).when(sessions).revokeUser(id);
        new AccountService(store, sessions, clock).delete(id);
        verify(sessions, times(2)).revokeUser(id);
    }

    private static PortFailure unavailable() {
        return new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true);
    }

    private static void assertReason(Runnable call, AuthFailure.Reason reason) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure -> assertThat(failure.reason()).isEqualTo(reason));
    }
}
