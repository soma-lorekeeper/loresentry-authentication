package com.loresentry.authentication.application.port.out;

import com.loresentry.authentication.domain.User;
import com.loresentry.authentication.domain.OAuthIdentity;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Mutations return only after commit. Absence is Optional.empty(), never a storage error. */
public interface AccountStore {
    record Account(User user, OAuthIdentity identity) {}
    Optional<Account> findByIdentity(String provider, String providerId);
    Optional<Account> findById(UUID userId);
    Account create(User user, OAuthIdentity identity);
    Account updateEmail(String provider, String providerId, String email, Instant updatedAt);
    Optional<Account> rename(UUID userId, String displayName, Instant updatedAt);
}
