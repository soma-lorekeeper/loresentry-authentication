package com.loresentry.authentication.application.service;

import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.*;
import java.time.Clock;
import lombok.RequiredArgsConstructor;

/**
 * 검증된 외부 신원을 계정에 연결하고 동일 신원의 동시 가입 충돌을 처리한다.
 *
 * <p>가입 충돌 시 이미 커밋된 계정을 다시 조회한다. 기존 표시 이름은 보존하고 공급자가 전달한 비어 있지 않은 새 이메일만 갱신한다.
 */
@RequiredArgsConstructor
public final class RegistrationService implements RegisterIdentityUseCase {
    private final AccountStore accounts;
    private final UserIdGenerator ids;
    private final Clock clock;

    @Override
    public User register(OidcClient.Identity identity) {
        if (identity == null
                || invalid(identity.provider(), 32)
                || invalid(identity.subject(), 255)
                || (identity.email() != null
                        && identity.email().codePointCount(0, identity.email().length()) > 320)) {
            throw new AuthFailure(OAUTH_IDENTITY_INVALID);
        }
        try {
            var existing = accounts.findByIdentity(identity.provider(), identity.subject());
            if (existing.isPresent()) return refreshEmail(existing.get(), identity);
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var user = new User(ids.generate(), DisplayNames.initial(identity.name()), now, now);
            var email = hasEmail(identity.email()) ? identity.email() : null;
            try {
                return accounts.create(
                                user,
                                new OAuthIdentity(
                                        identity.provider(), identity.subject(), user.id(), email))
                        .user();
            } catch (PortFailure failure) {
                if (failure.kind() != PortFailure.Kind.IDENTITY_ALREADY_REGISTERED) throw failure;
                var winner =
                        accounts.findByIdentity(identity.provider(), identity.subject())
                                .orElseThrow(() -> new AuthFailure(LOGIN_UNAVAILABLE));
                return refreshEmail(winner, identity);
            }
        } catch (PortFailure failure) {
            throw new AuthFailure(LOGIN_UNAVAILABLE);
        }
    }

    private User refreshEmail(AccountStore.Account account, OidcClient.Identity identity) {
        if (!hasEmail(identity.email()) || identity.email().equals(account.identity().email()))
            return account.user();
        return accounts.updateEmail(
                        identity.provider(),
                        identity.subject(),
                        identity.email(),
                        clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                .user();
    }

    private static boolean hasEmail(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean invalid(String value, int limit) {
        return value == null || value.isBlank() || value.codePointCount(0, value.length()) > limit;
    }
}
