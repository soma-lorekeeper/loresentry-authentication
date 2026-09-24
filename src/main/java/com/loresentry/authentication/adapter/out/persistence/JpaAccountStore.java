package com.loresentry.authentication.adapter.out.persistence;

import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.*;
import java.time.Instant;
import java.util.Optional;
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
    private final AccountTransactions transactions;

    public Optional<Account> findByIdentity(String provider, String subject) {
        return invoke(() -> transactions.findByIdentity(provider, subject));
    }

    public Optional<Account> findById(UUID id) {
        return invoke(() -> transactions.findById(id));
    }

    public Account create(User user, OAuthIdentity identity) {
        return invoke(() -> transactions.create(user, identity));
    }

    public Account updateEmail(String provider, String subject, String email, Instant time) {
        return invoke(() -> transactions.updateEmail(provider, subject, email, time));
    }

    public Optional<Account> rename(UUID id, String name, Instant time) {
        return invoke(() -> transactions.rename(id, name, time));
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
            throw new PortFailure(
                    PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false);
        }
    }
}
