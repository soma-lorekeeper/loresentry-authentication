package com.loresentry.authentication.adapter.out.persistence;

import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.*;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.stereotype.Component;

/**
 * JPA 트랜잭션 결과와 예외를 계정 저장소 포트의 계약으로 변환한다.
 *
 * <p>별도 빈인 AccountTransactions의 트랜잭션 프록시를 호출하므로 커밋 실패까지 이 경계에서 처리한다. 외부 신원 기본 키 충돌만 동시 가입으로 분류하고
 * 나머지는 저장소 장애로 보고한다.
 */
@Component
@RequiredArgsConstructor
public class JpaAccountStore implements AccountStore {
    /** 로그에 그대로 남겨도 되는 공급자 코드. 지금은 Google 로그인만 있다. 목록 밖의 값은 other 로 남는다. */
    private static final Set<String> PROVIDERS = Set.of("google");

    private final AccountTransactions transactions;

    public Optional<Account> findByIdentity(String provider, String subject) {
        // Google subject는 사람을 특정하는 값이라 남기지 않는다. 공급자는 정해진 코드만 남긴다
        AppLogger.current()
                .info("account find by identity", LogField.code("provider", provider, PROVIDERS));
        return invoke(() -> transactions.findByIdentity(provider, subject));
    }

    public Optional<Account> findById(UUID id) {
        AppLogger.current().info("account find");
        return invoke(() -> transactions.findById(id));
    }

    public Account create(User user, OAuthIdentity identity) {
        // 표시 이름·이메일·Google subject는 남기지 않는다
        AppLogger.current().info("account create");
        return invoke(() -> transactions.create(user, identity));
    }

    public Account updateEmail(String provider, String subject, String email, Instant time) {
        // 이메일·Google subject는 사람을 특정하는 값이라 남기지 않는다
        AppLogger.current()
                .info("account email update", LogField.code("provider", provider, PROVIDERS));
        return invoke(() -> transactions.updateEmail(provider, subject, email, time));
    }

    public Optional<Account> rename(UUID id, String name, Instant time) {
        AppLogger.current().info("account rename");
        return invoke(() -> transactions.rename(id, name, time));
    }

    public Optional<Account> changeLocale(UUID id, SupportedLocale locale, Instant time) {
        AppLogger.current().info("account locale change");
        return invoke(() -> transactions.changeLocale(id, locale, time));
    }

    public boolean completeOnboarding(UUID id, Instant time) {
        AppLogger.current().info("account onboarding complete");
        return invoke(() -> transactions.completeOnboarding(id, time));
    }

    public boolean recordAcquisition(UUID id, Acquisition acquisition, Instant time) {
        AppLogger.current().info("account acquisition record");
        return invoke(() -> transactions.recordAcquisition(id, acquisition, time));
    }

    public boolean delete(UUID id) {
        AppLogger.current().info("account delete");
        return invoke(() -> transactions.delete(id));
    }

    private <T> T invoke(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause()) {
                if (cause instanceof ConstraintViolationException constraint
                        && "23505".equals(constraint.getSQLState())
                        && "pk_oauth_identities".equals(constraint.getConstraintName())) {
                    throw new PortFailure(
                            PortFailure.Kind.IDENTITY_ALREADY_REGISTERED,
                            PortFailure.Execution.UNKNOWN,
                            false);
                }
            }
            // 원인은 예외 클래스 이름과 스택 프레임만 남긴다(메시지에는 SQL·키·값이 섞일 수 있다). 이 실패는 PortFailure 로 바뀌어 원인이 사라진다
            AppLogger.current()
                    .warn(
                            "account store call failed",
                            LogField.errorType(error.getClass().getName()),
                            LogField.safeStackTrace(error));
            throw new PortFailure(
                    PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false);
        }
    }
}
