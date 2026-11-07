package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.PasswordAuthService;
import com.loresentry.authentication.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PasswordAuthServiceTest {
    final AccountStore accounts = mock(AccountStore.class);
    final PasswordHasher hasher = mock(PasswordHasher.class);
    final UserIdGenerator userIds = mock(UserIdGenerator.class);
    final SessionIdGenerator sessionIds = mock(SessionIdGenerator.class);
    final LoginSessionStore sessions = mock(LoginSessionStore.class);
    final TermsLoginGate terms = mock(TermsLoginGate.class);
    final Instant now = Instant.parse("2026-11-07T00:00:00Z");
    final UUID userId = UUID.randomUUID();
    final SessionId sessionId = new SessionId("A".repeat(43));
    final Instant expiry = now.plusSeconds(1209600);
    final PasswordAuthService service =
            new PasswordAuthService(
                    accounts,
                    hasher,
                    userIds,
                    sessionIds,
                    sessions,
                    terms,
                    Clock.fixed(now, ZoneOffset.UTC));

    AccountStore.Account account(String loginId, String name) {
        var user = new User(userId, name, now, now, null, null);
        return new AccountStore.Account(user, new OAuthIdentity("password", loginId, userId, null));
    }

    void sessionWorks() {
        when(sessionIds.generate()).thenReturn(sessionId);
        when(sessions.replace(userId, sessionId)).thenReturn(Optional.of(expiry));
        when(accounts.findById(userId)).thenReturn(Optional.of(account("writer", "Writer")));
        when(terms.check(userId)).thenReturn(Optional.empty());
    }

    @Test
    void signUpNormalisesTheLoginIdHashesThePasswordAndIssuesASession() {
        when(userIds.generate()).thenReturn(userId);
        when(hasher.hash("correct-horse-7")).thenReturn("$hash");
        when(accounts.createWithPassword(any(), any(), eq("$hash")))
                .thenAnswer(
                        call -> new AccountStore.Account(call.getArgument(0), call.getArgument(1)));
        sessionWorks();
        var result =
                service.signUp(
                        new PasswordAuthUseCase.SignUp("  Writer.One ", "correct-horse-7", null));
        assertThat(result.status()).isEqualTo(LoginUseCase.LoginStatus.AUTHENTICATED);
        assertThat(result.sessionId()).isEqualTo(sessionId);
        assertThat(result.expiresAt()).isEqualTo(expiry);
        var identity = org.mockito.ArgumentCaptor.forClass(OAuthIdentity.class);
        var user = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(accounts).createWithPassword(user.capture(), identity.capture(), eq("$hash"));
        assertThat(identity.getValue())
                .isEqualTo(new OAuthIdentity("password", "writer.one", userId, null));
        assertThat(user.getValue().displayName()).isEqualTo("writer.one");
        assertThat(user.getValue().createdAt()).isEqualTo(now);
        assertThat(result.toString()).doesNotContain("A".repeat(43));
    }

    @Test
    void signUpKeepsAGivenDisplayNameAndReturnsTermsRequiredWhenTheGateAsks() {
        when(userIds.generate()).thenReturn(userId);
        when(hasher.hash(any())).thenReturn("$hash");
        when(accounts.createWithPassword(any(), any(), any()))
                .thenAnswer(
                        call -> new AccountStore.Account(call.getArgument(0), call.getArgument(1)));
        var consent = new ConsentId("B".repeat(42) + "A");
        when(terms.check(userId))
                .thenReturn(
                        Optional.of(new TermsLoginGate.Required(consent, now.plusSeconds(1800))));
        var result =
                service.signUp(
                        new PasswordAuthUseCase.SignUp("judge1", "correct-horse-7", " 심사위원 "));
        assertThat(result.status()).isEqualTo(LoginUseCase.LoginStatus.TERMS_REQUIRED);
        assertThat(result.consentRequestId()).isEqualTo(consent);
        assertThat(result.sessionId()).isNull();
        var user = org.mockito.ArgumentCaptor.forClass(User.class);
        verify(accounts).createWithPassword(user.capture(), any(), any());
        assertThat(user.getValue().displayName()).isEqualTo("심사위원");
        verifyNoInteractions(sessions);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "ab",
                "has space",
                "UPPER!",
                "한글아이디",
                "-starts",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
            })
    void signUpRejectsLoginIdsOutsideTheRule(String loginId) {
        assertThatThrownBy(
                        () ->
                                service.signUp(
                                        new PasswordAuthUseCase.SignUp(
                                                loginId, "correct-horse-7", null)))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(AuthFailure.Reason.INVALID_LOGIN_ID));
        verifyNoInteractions(accounts, hasher);
    }

    @ParameterizedTest
    @ValueSource(strings = {"short", "has space12", "", "tab\there1"})
    void signUpRejectsPasswordsOutsideTheRule(String password) {
        assertThatThrownBy(
                        () ->
                                service.signUp(
                                        new PasswordAuthUseCase.SignUp("writer", password, null)))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(AuthFailure.Reason.INVALID_PASSWORD));
        assertThatThrownBy(
                        () ->
                                service.signUp(
                                        new PasswordAuthUseCase.SignUp(
                                                "writer", "a".repeat(73), null)))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(AuthFailure.Reason.INVALID_PASSWORD));
        verifyNoInteractions(accounts, hasher);
    }

    @Test
    void duplicateLoginIdBecomesLoginIdTakenAndOtherStoreFailuresBecomeUnavailable() {
        when(userIds.generate()).thenReturn(userId);
        when(hasher.hash(any())).thenReturn("$hash");
        when(accounts.createWithPassword(any(), any(), any()))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.IDENTITY_ALREADY_REGISTERED,
                                PortFailure.Execution.UNKNOWN,
                                false))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true));
        var command = new PasswordAuthUseCase.SignUp("writer", "correct-horse-7", null);
        assertThatThrownBy(() -> service.signUp(command))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(AuthFailure.Reason.LOGIN_ID_TAKEN));
        assertThatThrownBy(() -> service.signUp(command))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(AuthFailure.Reason.LOGIN_UNAVAILABLE));
    }

    @Test
    void loginMatchesTheStoredHashAndIssuesASession() {
        when(accounts.findByIdentity("password", "writer"))
                .thenReturn(Optional.of(account("writer", "Writer")));
        when(accounts.passwordHash(userId)).thenReturn(Optional.of("$hash"));
        when(hasher.matches("correct-horse-7", "$hash")).thenReturn(true);
        sessionWorks();
        var result = service.login(new PasswordAuthUseCase.Login("Writer", "correct-horse-7"));
        assertThat(result.status()).isEqualTo(LoginUseCase.LoginStatus.AUTHENTICATED);
        assertThat(result.sessionId()).isEqualTo(sessionId);
    }

    @Test
    void loginFailsTheSameWayForUnknownIdsWrongPasswordsAndMalformedInput() {
        when(accounts.findByIdentity("password", "nobody")).thenReturn(Optional.empty());
        when(accounts.findByIdentity("password", "writer"))
                .thenReturn(Optional.of(account("writer", "Writer")));
        when(accounts.passwordHash(userId)).thenReturn(Optional.of("$hash"));
        when(hasher.matches("wrong-password-7", "$hash")).thenReturn(false);
        for (var command :
                List.of(
                        new PasswordAuthUseCase.Login("nobody", "correct-horse-7"),
                        new PasswordAuthUseCase.Login("writer", "wrong-password-7"),
                        new PasswordAuthUseCase.Login("writer", ""),
                        new PasswordAuthUseCase.Login("not valid!", "correct-horse-7"),
                        new PasswordAuthUseCase.Login(null, "correct-horse-7"))) {
            assertThatThrownBy(() -> service.login(command))
                    .isInstanceOfSatisfying(
                            AuthFailure.class,
                            failure ->
                                    assertThat(failure.reason())
                                            .isEqualTo(AuthFailure.Reason.INVALID_CREDENTIALS));
        }
        verifyNoInteractions(sessions);
    }

    @Test
    void loginStoreFailureIsUnavailable() {
        when(accounts.findByIdentity(any(), any()))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true));
        assertThatThrownBy(
                        () ->
                                service.login(
                                        new PasswordAuthUseCase.Login("writer", "correct-horse-7")))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(AuthFailure.Reason.LOGIN_UNAVAILABLE));
    }
}
