package com.loresentry.authentication.adapter.out.persistence;

import com.loresentry.authentication.adapter.out.event.UserDeletedMessages;
import com.loresentry.authentication.adapter.out.outbox.OutboxRepository;
import com.loresentry.authentication.application.port.out.AccountStore.Account;
import com.loresentry.authentication.domain.*;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogLayer;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 계정과 외부 신원의 조회·변경을 Spring 트랜잭션 안에서 수행한다.
 *
 * <p>JpaAccountStore가 이 빈의 프록시를 통해 호출하여 트랜잭션 완료 후 결과를 받는다. 사용자당 외부 신원은 하나를 전제로 하며, 여러 신원이 조회되면 선택
 * 규칙이 없어 실패한다.
 */
// log.layer: 계정 테이블을 읽고 쓰는 트랜잭션 단위라 repository 레이어다(클래스 이름 끝 규칙에 맞지 않아 직접 지정)
@LogLayer("repository")
@Component
@RequiredArgsConstructor
public class AccountTransactions {
    private static final int[] ACQUISITION_TYPES = {
        Types.OTHER,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.VARCHAR,
        Types.TIMESTAMP,
        Types.TIMESTAMP
    };
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final IdentityRepository identities;
    private final AccountEntityMapper mapper;

    /** 계정 삭제가 UserDeleted 를 같은 트랜잭션으로 outbox 에 쓴다. */
    private final OutboxRepository outbox;

    @Transactional(readOnly = true)
    public Optional<Account> findByIdentity(String provider, String subject) {
        AppLogger.current().info("account find by identity transaction");
        return identities.findById(new OAuthIdentityId(provider, subject)).map(mapper::toAccount);
    }

    @Transactional(readOnly = true)
    public Optional<Account> findById(UUID userId) {
        AppLogger.current().info("account find transaction");
        return identityForUser(userId).map(mapper::toAccount);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Account create(User user, OAuthIdentity identity) {
        AppLogger.current().info("account create transaction");
        var entity = mapper.toEntity(user);
        var link = mapper.toEntity(identity, entity);
        entityManager.persist(entity);
        entityManager.persist(link);
        entityManager.flush();
        return mapper.toAccount(link);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Account updateEmail(String provider, String subject, String email, Instant time) {
        AppLogger.current().info("account email update transaction");
        var identity = identities.findById(new OAuthIdentityId(provider, subject)).orElseThrow();
        identity.updateEmail(email);
        identity.getUser().touch(time);
        return mapper.toAccount(identity);
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Optional<Account> rename(UUID userId, String name, Instant time) {
        AppLogger.current().info("account rename transaction");
        return identityForUser(userId)
                .map(
                        identity -> {
                            identity.getUser().rename(name, time);
                            return mapper.toAccount(identity);
                        });
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public Optional<Account> changeLocale(UUID userId, SupportedLocale locale, Instant time) {
        AppLogger.current().info("account locale change transaction");
        return identityForUser(userId)
                .map(
                        identity -> {
                            identity.getUser().changeLocale(mapper.localeCode(locale), time);
                            return mapper.toAccount(identity);
                        });
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean completeOnboarding(UUID userId, Instant time) {
        AppLogger.current().info("account onboarding complete transaction");
        return entityManager
                        .createNativeQuery(
                                "UPDATE users SET onboarding_completed_at = COALESCE(onboarding_completed_at, ?1) WHERE id = ?2")
                        .setParameter(1, time)
                        .setParameter(2, userId)
                        .executeUpdate()
                > 0;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean recordAcquisition(UUID userId, Acquisition acquisition, Instant time) {
        AppLogger.current().info("account acquisition record transaction");
        var open =
                jdbc.queryForList(
                        "SELECT onboarding_completed_at IS NULL FROM users WHERE id = ? FOR SHARE",
                        Boolean.class,
                        userId);
        if (open.isEmpty()) return false;
        if (!open.getFirst()) {
            // 온보딩을 마친 계정은 유입 경로를 저장하지 않고 성공으로 돌려준다. 저장하지 않은 것을 결과 줄로 남긴다
            AppLogger.current().info("account acquisition record skipped: onboarding completed");
            return true;
        }
        int inserted =
                jdbc.update(
                        "INSERT INTO user_acquisitions(user_id, utm_source, utm_medium, utm_campaign, utm_term, utm_content, click_id, click_id_type, referrer_host, landing_path, landed_at, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (user_id) DO NOTHING",
                        new Object[] {
                            userId,
                            acquisition.utmSource(),
                            acquisition.utmMedium(),
                            acquisition.utmCampaign(),
                            acquisition.utmTerm(),
                            acquisition.utmContent(),
                            acquisition.clickId(),
                            acquisition.clickIdType(),
                            acquisition.referrerHost(),
                            acquisition.landingPath(),
                            acquisition.landedAt() == null
                                    ? null
                                    : Timestamp.from(acquisition.landedAt()),
                            Timestamp.from(time)
                        },
                        ACQUISITION_TYPES);
        if (inserted == 0) {
            // ON CONFLICT DO NOTHING: 이미 저장된 유입 경로가 있어 덮어쓰지 않았다. 응답은 똑같이 성공이다
            AppLogger.current().info("account acquisition record skipped: already recorded");
        }
        return true;
    }

    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public boolean delete(UUID userId) {
        AppLogger.current().info("account delete transaction");
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
