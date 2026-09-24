package com.loresentry.authentication.adapter.out.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface IdentityRepository extends JpaRepository<OAuthIdentityEntity, OAuthIdentityId> {
    @Override
    @EntityGraph(attributePaths = "user")
    Optional<OAuthIdentityEntity> findById(OAuthIdentityId id);

    @Query("select i from OAuthIdentityEntity i join fetch i.user u where u.id = :userId")
    List<OAuthIdentityEntity> findByUserId(UUID userId);
}
