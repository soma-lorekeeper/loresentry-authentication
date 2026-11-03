package com.loresentry.authentication.application.service;

import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.Acquisition;
import com.loresentry.authentication.domain.DisplayNames;
import com.loresentry.authentication.domain.SupportedLocale;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import lombok.RequiredArgsConstructor;

/** 표시 이름·언어 규칙을 적용하고 계정 저장소 결과를 애플리케이션 프로필과 실패 이유로 변환한다. */
@RequiredArgsConstructor
public final class AccountService implements AccountUseCase {
    private final AccountStore accounts;
    private final LoginSessionStore sessions;
    private final Clock clock;

    public Profile get(UUID userId) {
        AppLogger.current().info("account get");
        if (userId == null) throw new AuthFailure(USER_CONTEXT_REQUIRED);
        try {
            return profile(
                    accounts.findById(userId).orElseThrow(() -> new AuthFailure(USER_NOT_FOUND)));
        } catch (PortFailure failure) {
            throw new AuthFailure(ACCOUNT_UNAVAILABLE);
        }
    }

    public Profile rename(UUID userId, String displayName) {
        // 표시 이름은 개인정보라 남기지 않는다
        AppLogger.current().info("account rename");
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

    public Profile changeLocale(UUID userId, String locale) {
        AppLogger.current().info("account locale change");
        if (userId == null) throw new AuthFailure(USER_CONTEXT_REQUIRED);
        var value =
                SupportedLocale.find(locale).orElseThrow(() -> new AuthFailure(INVALID_REQUEST));
        try {
            return profile(
                    accounts.changeLocale(userId, value, now())
                            .orElseThrow(() -> new AuthFailure(USER_NOT_FOUND)));
        } catch (PortFailure failure) {
            throw new AuthFailure(ACCOUNT_UNAVAILABLE);
        }
    }

    public void completeOnboarding(UUID userId) {
        AppLogger.current().info("account onboarding complete");
        if (userId == null) throw new AuthFailure(USER_CONTEXT_REQUIRED);
        final boolean found;
        try {
            found = accounts.completeOnboarding(userId, now());
        } catch (PortFailure failure) {
            throw new AuthFailure(ACCOUNT_UNAVAILABLE);
        }
        if (!found) throw new AuthFailure(USER_NOT_FOUND);
    }

    public void recordAcquisition(UUID userId, Acquisition acquisition) {
        // 클릭 ID·UTM 값은 남기지 않는다
        AppLogger.current().info("account acquisition record");
        if (userId == null) throw new AuthFailure(USER_CONTEXT_REQUIRED);
        if (acquisition == null) throw new AuthFailure(INVALID_REQUEST);
        final boolean found;
        try {
            found = accounts.recordAcquisition(userId, acquisition, now());
        } catch (PortFailure failure) {
            throw new AuthFailure(ACCOUNT_UNAVAILABLE);
        }
        if (!found) throw new AuthFailure(USER_NOT_FOUND);
    }

    public void delete(UUID userId) {
        AppLogger.current().info("account delete");
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
            // 탈퇴는 이미 확정됐으므로 실패를 돌려주지 않는다. 그 사이 만들어진 세션이 남았을 수 있어 warn으로 남긴다
            AppLogger.current()
                    .warn(
                            "account delete session cleanup failed",
                            LogField.errorType(ignored.kind().name()));
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
                account.user().onboardingCompletedAt() != null,
                account.user().locale());
    }
}
