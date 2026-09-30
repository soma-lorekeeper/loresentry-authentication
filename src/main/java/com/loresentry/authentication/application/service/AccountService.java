package com.loresentry.authentication.application.service;

import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.DisplayNames;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** 표시 이름 규칙을 적용하고 계정 저장소 결과를 애플리케이션 프로필과 실패 이유로 변환한다. */
@RequiredArgsConstructor
public final class AccountService implements AccountUseCase {
    private final AccountStore accounts;
    private final LoginSessionStore sessions;
    private final Clock clock;

    public Profile get(UUID userId) {
        if (userId == null) throw new AuthFailure(USER_CONTEXT_REQUIRED);
        try {
            return profile(
                    accounts.findById(userId).orElseThrow(() -> new AuthFailure(USER_NOT_FOUND)));
        } catch (PortFailure failure) {
            throw new AuthFailure(ACCOUNT_UNAVAILABLE);
        }
    }

    public Profile rename(UUID userId, String displayName) {
        if (userId == null) throw new AuthFailure(USER_CONTEXT_REQUIRED);
        final String name;
        try {
            name = DisplayNames.edited(displayName);
        } catch (DisplayNames.InvalidDisplayName failure) {
            throw new AuthFailure(INVALID_DISPLAY_NAME);
        }
        try {
            return profile(
                    accounts.rename(userId, name, now())
                            .orElseThrow(() -> new AuthFailure(USER_NOT_FOUND)));
        } catch (PortFailure failure) {
            throw new AuthFailure(ACCOUNT_UNAVAILABLE);
        }
    }

    public void completeOnboarding(UUID userId) {
        if (userId == null) throw new AuthFailure(USER_CONTEXT_REQUIRED);
        final boolean found;
        try {
            found = accounts.completeOnboarding(userId, now());
        } catch (PortFailure failure) {
            throw new AuthFailure(ACCOUNT_UNAVAILABLE);
        }
        if (!found) throw new AuthFailure(USER_NOT_FOUND);
    }

    public void delete(UUID userId) {
        if (userId == null) throw new AuthFailure(USER_CONTEXT_REQUIRED);
        final boolean deleted;
        try {
            sessions.revokeUser(userId);
            deleted = accounts.delete(userId);
        } catch (PortFailure failure) {
            throw new AuthFailure(ACCOUNT_UNAVAILABLE);
        }
        if (!deleted) throw new AuthFailure(USER_NOT_FOUND);
        try {
            sessions.revokeUser(userId);
        } catch (PortFailure ignored) {
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static Profile profile(AccountStore.Account account) {
        return new Profile(
                account.user().id(),
                account.user().displayName(),
                account.identity().email(),
                account.user().onboardingCompletedAt() != null);
    }
}
