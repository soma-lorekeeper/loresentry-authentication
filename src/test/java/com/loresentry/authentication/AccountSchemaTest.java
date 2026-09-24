package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.adapter.out.persistence.*;
import com.loresentry.authentication.application.port.out.UserIdGenerator;
import com.loresentry.authentication.domain.*;
import com.loresentry.authentication.support.DatabaseTestSupport;
import com.loresentry.authentication.support.TestInfrastructure;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
class AccountSchemaTest extends DatabaseTestSupport {
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UserIdGenerator ids;
    @Autowired AccountEntityMapper mapper;

    @Test
    void flywaySchemaAcceptsUuidV7AndMultipleIdentitiesWithoutEmailUniqueness() throws Exception {
        var userId = ids.generate();
        assertThat(userId.version()).isEqualTo(7);
        var now = Instant.parse("2026-09-17T00:00:00Z");
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(
                status -> {
                    var user = mapper.toEntity(new User(userId, "사용자", now, now));
                    entityManager.persist(user);
                    for (var identity :
                            java.util.List.of(
                                    new OAuthIdentity("google", "first-" + userId, userId, null),
                                    new OAuthIdentity(
                                            "google",
                                            "second-" + userId,
                                            userId,
                                            "same@example.test"),
                                    new OAuthIdentity(
                                            "other",
                                            "third-" + userId,
                                            userId,
                                            "same@example.test"))) {
                        var link = mapper.toEntity(identity, user);
                        assertThat(link.getUser()).isSameAs(user);
                        entityManager.persist(link);
                    }
                    entityManager.flush();
                });
        User loaded =
                tx.execute(status -> mapper.toDomain(entityManager.find(UserEntity.class, userId)));
        assertThat(loaded).isEqualTo(new User(userId, "사용자", now, now));
        tx.executeWithoutResult(
                status -> {
                    var link =
                            entityManager.find(
                                    OAuthIdentityEntity.class,
                                    new OAuthIdentityId("google", "first-" + userId));
                    var account = mapper.toAccount(link);
                    assertThat(account.user()).isEqualTo(loaded);
                    assertThat(account.identity())
                            .isEqualTo(
                                    new OAuthIdentity("google", "first-" + userId, userId, null));
                    var other =
                            entityManager.find(
                                    OAuthIdentityEntity.class,
                                    new OAuthIdentityId("other", "third-" + userId));
                    assertThat(mapper.toDomain(other))
                            .isEqualTo(
                                    new OAuthIdentity(
                                            "other",
                                            "third-" + userId,
                                            userId,
                                            "same@example.test"));
                });
        try (var connection = TestInfrastructure.connection();
                var statement = connection.createStatement()) {
            try (var result =
                    statement.executeQuery(
                            "SELECT success FROM flyway_schema_history WHERE version = '2'")) {
                assertThat(result.next()).isTrue();
                assertThat(result.getBoolean(1)).isTrue();
            }
            assertThatThrownBy(
                            () ->
                                    statement.executeUpdate(
                                            "INSERT INTO oauth_identities(provider,provider_id,user_id) VALUES ('google','first-"
                                                    + userId
                                                    + "','"
                                                    + userId
                                                    + "')"))
                    .isInstanceOf(java.sql.SQLException.class)
                    .extracting("SQLState")
                    .isEqualTo("23505");
        }
    }

    @Test
    void compositeKeyEqualityUsesBothOriginalIdentifiers() {
        assertThat(new OAuthIdentityId("google", "CaseID"))
                .isEqualTo(new OAuthIdentityId("google", "CaseID"));
        assertThat(new OAuthIdentityId("google", "CaseID"))
                .isNotEqualTo(new OAuthIdentityId("google", "caseid"));
        assertThat(new OAuthIdentityId("google", "CaseID"))
                .isNotEqualTo(new OAuthIdentityId("other", "CaseID"));
    }
}
