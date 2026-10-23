package com.loresentry.authentication.adapter.out.persistence;

import com.loresentry.authentication.adapter.out.event.UserDeletedMessages;
import com.loresentry.authentication.adapter.out.outbox.OutboxRepository;
import com.loresentry.authentication.application.port.out.AccountStore.Account;
import com.loresentry.authentication.domain.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정과 외부 신원의 조회·변경을 Spring 트랜잭션 안에서 수행한다.
 *
 * <p>JpaAccountStore가 이 빈의 프록시를 통해 호출하여 트랜잭션 완료 후 결과를 받는다. 사용자당 외부 신원은 하나를 전제로 하며, 여러 신원이 조회되면 선택
 * 규칙이 없어 실패한다.
 */
@Component
@RequiredArgsConstructor
public class AccountTransactions {
    private final EntityManager entityManager;
    private final IdentityRepository identities;
    private final AccountEntityMapper mapper;

    /** 계정 삭제가 UserDeleted 를 같은 트랜잭션으로 outbox 에 쓴다. */
    private final OutboxRepository outbox;

    @Transactional(readOnly = true)
    public Optional<Account> findByIdentity(String provider, String subject) {
        return identities.findById(new OAuthIdentityId(provider, subject)).map(mapper::toAccount);
    }

    @Transactional(readOnly = true)
    public Optional<Account> findById(UUID userId) {
        return identityForUser(userId).map(mapper::toAccount);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Account create(User user, OAuthIdentity identity) {
        var entity = mapper.toEntity(user);
        var link = mapper.toEntity(identity, entity);
        entityManager.persist(entity);
        entityManager.persist(link);
        entityManager.flush();
        return mapper.toAccount(link);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Account updateEmail(String provider, String subject, String email, Instant time) {
        var identity = identities.findById(new OAuthIdentityId(provider, subject)).orElseThrow();
        identity.updateEmail(email);
        identity.getUser().touch(time);
        return mapper.toAccount(identity);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Optional<Account> rename(UUID userId, String name, Instant time) {
        return identityForUser(userId)
                .map(
                        identity -> {
                            identity.getUser().rename(name, time);
                            return mapper.toAccount(identity);
                        });
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Optional<Account> changeLocale(UUID userId, SupportedLocale locale, Instant time) {
        return identityForUser(userId)
                .map(
                        identity -> {
                            identity.getUser().changeLocale(mapper.localeCode(locale), time);
                            return mapper.toAccount(identity);
                        });
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean completeOnboarding(UUID userId, Instant time) {
        return entityManager
                        .createNativeQuery(
                                "UPDATE users SET onboarding_completed_at = COALESCE(onboarding_completed_at, ?1) WHERE id = ?2")
                        .setParameter(1, time)
                        .setParameter(2, userId)
                        .executeUpdate()
                > 0;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean delete(UUID userId) {
        for (var table : List.of("user_terms_acceptances", "oauth_identities")) {
            entityManager
                    .createNativeQuery("DELETE FROM " + table + " WHERE user_id = ?1")
                    .setParameter(1, userId)
                    .executeUpdate();
        }
        boolean deleted =
                entityManager
                                .createNativeQuery("DELETE FROM users WHERE id = ?1")
                                .setParameter(1, userId)
                                .executeUpdate()
                        > 0;
        // 계정을 실제로 지웠을 때만 UserDeleted 이벤트를 outbox 에 남긴다. 삭제와 같은 트랜잭션이라 둘은 함께
        // 확정되거나 함께 취소된다 — 계정은 사라졌는데 이벤트가 없거나, 그 반대인 상태가 생기지 않는다.
        // Kafka 로 보내는 일은 여기서 하지 않는다. outbox relay 가 이 행을 읽어 나중에 발행한다.
        if (deleted) {
            outbox.append(UserDeletedMessages.userDeleted(userId));
        }
        return deleted;
    }

    private Optional<OAuthIdentityEntity> identityForUser(UUID userId) {
        var results = identities.findByUserId(userId);
        // Account linking and the associated email selection rule are deliberately not implemented.
        if (results.size() > 1)
            throw new IllegalStateException(
                    "Multiple identities require an account linking policy");
        return results.stream().findFirst();
    }
}
