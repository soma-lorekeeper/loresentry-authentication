package com.loresentry.authentication.adapter.out.persistence;

import lombok.RequiredArgsConstructor;
import com.loresentry.authentication.application.port.out.AccountStore.Account;
import com.loresentry.authentication.domain.*;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class AccountTransactions {
    private final EntityManager entityManager;
    private final IdentityRepository identities;
    private final AccountEntityMapper mapper;

    @Transactional(readOnly = true)
    public Optional<Account> findByIdentity(String provider, String subject) {
        return identities.findById(new OAuthIdentityId(provider, subject)).map(mapper::toAccount);
    }

    @Transactional(readOnly = true)
    public Optional<Account> findById(UUID userId) { return identityForUser(userId).map(mapper::toAccount); }

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
        return identityForUser(userId).map(identity -> {
            identity.getUser().rename(name, time);
            return mapper.toAccount(identity);
        });
    }

    private Optional<OAuthIdentityEntity> identityForUser(UUID userId) {
        var results = identities.findByUserId(userId);
        // Account linking and the associated email selection rule are deliberately not implemented.
        if (results.size() > 1) throw new IllegalStateException("Multiple identities require an account linking policy");
        return results.stream().findFirst();
    }
}
