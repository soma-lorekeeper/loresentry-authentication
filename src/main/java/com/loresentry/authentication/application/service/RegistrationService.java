package com.loresentry.authentication.application.service;

import static com.loresentry.authentication.application.port.in.AuthFailure.Reason.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.*;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.time.Clock;
import java.util.Set;
import lombok.RequiredArgsConstructor;

/**
 * 검증된 외부 신원을 계정에 연결하고 동일 신원의 동시 가입 충돌을 처리한다.
 *
 * <p>가입 충돌 시 이미 커밋된 계정을 다시 조회한다. 기존 표시 이름은 보존하고 공급자가 전달한 비어 있지 않은 새 이메일만 갱신한다.
 */
@RequiredArgsConstructor
public final class RegistrationService implements RegisterIdentityUseCase {
    /** 신원을 받아들이지 않은 이유(account register rejected 줄의 result.reason). 목록 밖의 값은 other로 남는다. */
    private static final Set<String> IDENTITY_REJECTIONS =
            Set.of("NO_IDENTITY", "INVALID_PROVIDER", "INVALID_SUBJECT", "EMAIL_TOO_LONG");

    private final AccountStore accounts;
    private final UserIdGenerator ids;
    private final Clock clock;

    @Override
    public User register(OidcClient.Identity identity) {
        // Google subject·이메일·이름은 사람을 특정하는 값이라 남기지 않는다
        AppLogger.current().info("account register");
        // 거절 이유. 예전의 한 줄 조건과 같은 순서로 검사해, 먼저 걸린 조건 하나를 로그에 남긴다(값 자체는 남기지 않는다)
        String rejected = null;
        if (identity == null) rejected = "NO_IDENTITY";
        else if (invalid(identity.provider(), 32)) rejected = "INVALID_PROVIDER";
        else if (invalid(identity.subject(), 255)) rejected = "INVALID_SUBJECT";
        else if (identity.email() != null
                && identity.email().codePointCount(0, identity.email().length()) > 320)
            rejected = "EMAIL_TOO_LONG";
        if (rejected != null) {
            AppLogger.current()
                    .warn(
                            "account register rejected",
                            LogField.errorType(OAUTH_IDENTITY_INVALID.name()),
                            LogField.resultCode("reason", rejected, IDENTITY_REJECTIONS));
            throw new AuthFailure(OAUTH_IDENTITY_INVALID);
        }
        try {
            var existing = accounts.findByIdentity(identity.provider(), identity.subject());
            if (existing.isPresent()) return refreshEmail(existing.get(), identity);
            var now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var user =
                    new User(
                            ids.generate(),
                            DisplayNames.initial(identity.name(), identity.email()),
                            now,
                            now,
                            null,
                            null);
            var email = hasEmail(identity.email()) ? identity.email() : null;
            try {
                var created =
                        accounts.create(
                                        user,
                                        new OAuthIdentity(
                                                identity.provider(),
                                                identity.subject(),
                                                user.id(),
                                                email))
                                .user();
                // 새로 만든 계정의 사용자. 이 뒤의 줄과 로그인 결과 줄에 user.id가 붙는다
                AppLogger.current().identify(created.id());
                // 가입 결과 줄. 새 계정이라 이메일 갱신은 없다
                AppLogger.current()
                        .info(
                                "account register completed",
                                LogField.result("created", true),
                                LogField.result("email_updated", false));
                return created;
            } catch (PortFailure failure) {
                if (failure.kind() != PortFailure.Kind.IDENTITY_ALREADY_REGISTERED) throw failure;
                // 같은 신원의 동시 가입 충돌. 먼저 커밋된 계정을 다시 읽어 그 계정으로 로그인한다
                AppLogger.current()
                        .warn(
                                "account register conflict",
                                LogField.errorType(failure.kind().name()));
                var winner =
                        accounts.findByIdentity(identity.provider(), identity.subject())
                                .orElseThrow(
                                        () -> {
                                            // 충돌을 알렸는데 먼저 커밋된 계정을 다시 읽지 못했다. 503으로 멈춘다
                                            AppLogger.current()
                                                    .warn(
                                                            "account register conflict: committed account missing",
                                                            LogField.errorType(
                                                                    LOGIN_UNAVAILABLE.name()));
                                            return new AuthFailure(LOGIN_UNAVAILABLE);
                                        });
                return refreshEmail(winner, identity);
            }
        } catch (PortFailure failure) {
            throw new AuthFailure(LOGIN_UNAVAILABLE);
        }
    }

    private User refreshEmail(AccountStore.Account account, OidcClient.Identity identity) {
        // 이미 있는 계정(먼저 커밋된 계정 포함)의 사용자. 이 뒤의 줄(이메일 갱신 store 줄)과 로그인 결과 줄에 user.id가 붙는다
        AppLogger.current().identify(account.user().id());
        if (!hasEmail(identity.email()) || identity.email().equals(account.identity().email())) {
            // 가입 결과 줄(기존 계정, 이메일 그대로). 이메일 값은 남기지 않는다
            AppLogger.current()
                    .info(
                            "account register completed",
                            LogField.result("created", false),
                            LogField.result("email_updated", false));
            return account.user();
        }
        var updated =
                accounts.updateEmail(
                                identity.provider(),
                                identity.subject(),
                                identity.email(),
                                clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS))
                        .user();
        // 가입 결과 줄(기존 계정, 공급자가 준 새 이메일로 갱신). 이메일 값은 남기지 않는다
        AppLogger.current()
                .info(
                        "account register completed",
                        LogField.result("created", false),
                        LogField.result("email_updated", true));
        return updated;
    }

    private static boolean hasEmail(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean invalid(String value, int limit) {
        return value == null || value.isBlank() || value.codePointCount(0, value.length()) > limit;
    }
}
