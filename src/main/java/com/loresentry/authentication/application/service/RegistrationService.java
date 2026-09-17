package com.loresentry.authentication.application.service;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.*;
import java.time.Clock;
import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

public final class RegistrationService implements RegisterIdentityUseCase {
    private final AccountStore accounts;
    private final UserIdGenerator ids;
    private final Clock clock;
    public RegistrationService(AccountStore accounts, UserIdGenerator ids, Clock clock) {
        this.accounts = accounts; this.ids = ids; this.clock = clock;
    }

    @Override public User register(OidcClient.Identity identity) {
        if (identity == null || invalid(identity.provider(), 32) || invalid(identity.subject(), 255)
                || (identity.email() != null && identity.email().codePointCount(0, identity.email().length()) > 320)) {
            throw new AuthFailure(OAUTH_IDENTITY_INVALID);
        }
        try {
            var existing = accounts.findByIdentity(identity.provider(), identity.subject());
            if (existing.isPresent()) return refreshEmail(existing.get(), identity);
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var user = new User(ids.generate(), DisplayNames.initial(identity.name()), now, now);
            var email = hasEmail(identity.email()) ? identity.email() : null;
            try {
                return accounts.create(user, new OAuthIdentity(identity.provider(), identity.subject(), user.id(), email)).user();
            } catch (PortFailure failure) {
                if (failure.kind() != PortFailure.Kind.IDENTITY_ALREADY_REGISTERED) throw failure;
                var winner = accounts.findByIdentity(identity.provider(), identity.subject())
                        .orElseThrow(() -> new AuthFailure(LOGIN_UNAVAILABLE));
                return refreshEmail(winner, identity);
            }
        } catch (PortFailure failure) {
            throw new AuthFailure(LOGIN_UNAVAILABLE);
        }
    }

    private User refreshEmail(AccountStore.Account account, OidcClient.Identity identity) {
        if (!hasEmail(identity.email()) || identity.email().equals(account.identity().email())) return account.user();
        return accounts.updateEmail(identity.provider(), identity.subject(), identity.email(),
                clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS)).user();
    }
    private static boolean hasEmail(String value) { return value != null && !value.isBlank(); }
    private static boolean invalid(String value, int limit) { return value == null || value.isBlank() || value.codePointCount(0, value.length()) > limit; }
}
