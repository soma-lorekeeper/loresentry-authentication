package com.loresentry.authentication.application.service;

import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.PasswordAuthUseCase;
import com.loresentry.authentication.application.port.in.TermsLoginGate;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.*;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public final class PasswordAuthService implements PasswordAuthUseCase {
    private final AccountStore accounts;
    private final PasswordHasher hasher;
    private final UserIdGenerator userIds;
    private final SessionIdGenerator sessionIds;
    private final LoginSessionStore sessions;
    private final TermsLoginGate terms;
    private final Clock clock;

    @Override
    public Result signUp(SignUp command) {
        if (command == null) throw new AuthFailure(INVALID_REQUEST);
        var loginId = loginId(command.loginId());
        var password = password(command.password());
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var user =
                new User(
                        userIds.generate(),
                        DisplayNames.initial(loginId, null),
                        now,
                        now,
                        null,
                        null);
        try {
            var account =
                    accounts.createWithPassword(
                            user,
                            new OAuthIdentity(PasswordAccount.PROVIDER, loginId, user.id(), null),
                            hasher.hash(password));
            return issue(account.user().id());
        } catch (PortFailure failure) {
            if (failure.kind() == PortFailure.Kind.IDENTITY_ALREADY_REGISTERED)
                throw new AuthFailure(LOGIN_ID_TAKEN);
            throw new AuthFailure(LOGIN_UNAVAILABLE);
        } catch (AuthFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthFailure(INTERNAL_ERROR, null, failure);
        }
    }

    @Override
    public Result login(Login command) {
        if (command == null) throw new AuthFailure(INVALID_REQUEST);
        final String loginId;
        try {
            loginId = PasswordAccount.loginId(command.loginId());
        } catch (PasswordAccount.InvalidLoginId invalid) {
            throw new AuthFailure(INVALID_CREDENTIALS);
        }
        if (command.password() == null || command.password().isEmpty())
            throw new AuthFailure(INVALID_CREDENTIALS);
        try {
            var account =
                    accounts.findByIdentity(PasswordAccount.PROVIDER, loginId)
                            .orElseThrow(() -> new AuthFailure(INVALID_CREDENTIALS));
            var hash = accounts.passwordHash(account.user().id()).orElse(null);
            if (!hasher.matches(command.password(), hash))
                throw new AuthFailure(INVALID_CREDENTIALS);
            return issue(account.user().id());
        } catch (PortFailure failure) {
            throw new AuthFailure(LOGIN_UNAVAILABLE);
        } catch (AuthFailure failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new AuthFailure(INTERNAL_ERROR, null, failure);
        }
    }

    private Result issue(UUID userId) {
        var required = terms.check(userId);
        if (required.isPresent())
            return Result.termsRequired(required.get().id(), required.get().expiresAt());
        var issued = SessionIssuance.create(userId, sessionIds, sessions, accounts);
        return Result.authenticated(issued.id(), issued.expiresAt());
    }

    private static String loginId(String value) {
        try {
            return PasswordAccount.loginId(value);
        } catch (PasswordAccount.InvalidLoginId invalid) {
            throw new AuthFailure(INVALID_LOGIN_ID);
        }
    }

    private static String password(String value) {
        try {
            return PasswordAccount.password(value);
        } catch (PasswordAccount.InvalidPassword invalid) {
            throw new AuthFailure(INVALID_PASSWORD);
        }
    }
}
