package com.loresentry.authentication;

import com.loresentry.authentication.adapter.out.persistence.*;
import com.loresentry.authentication.application.port.out.UserIdGenerator;
import com.loresentry.authentication.domain.*;
import com.loresentry.authentication.support.DatabaseTestSupport;
import com.loresentry.authentication.support.TestInfrastructure;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class AccountSchemaTest extends DatabaseTestSupport {
    @Autowired EntityManager entityManager;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UserIdGenerator ids;

    @Test
    void flywaySchemaAcceptsUuidV7AndMultipleIdentitiesWithoutEmailUniqueness() throws Exception {
        var userId = ids.generate();
        assertThat(userId.version()).isEqualTo(7);
        var now = Instant.parse("2026-09-17T00:00:00Z");
        var tx = new TransactionTemplate(transactionManager);
        tx.executeWithoutResult(status -> {
            var user = new UserEntity(new User(userId, "사용자", now, now));
            entityManager.persist(user);
            entityManager.persist(new OAuthIdentityEntity(new OAuthIdentity("google", "first-" + userId, userId, null), user));
            entityManager.persist(new OAuthIdentityEntity(new OAuthIdentity("google", "second-" + userId, userId, "same@example.test"), user));
            entityManager.persist(new OAuthIdentityEntity(new OAuthIdentity("other", "third-" + userId, userId, "same@example.test"), user));
            entityManager.flush();
        });
        User loaded = tx.execute(status -> entityManager.find(UserEntity.class, userId).toDomain());
        assertThat(loaded).isEqualTo(new User(userId, "사용자", now, now));
        try (var connection = TestInfrastructure.connection(); var statement = connection.createStatement()) {
            try (var result = statement.executeQuery("SELECT success FROM flyway_schema_history WHERE version = '1'")) {
                assertThat(result.next()).isTrue(); assertThat(result.getBoolean(1)).isTrue();
            }
            assertThatThrownBy(() -> statement.executeUpdate("INSERT INTO oauth_identities(provider,provider_id,user_id) VALUES ('google','first-" + userId + "','" + userId + "')"))
                    .isInstanceOf(java.sql.SQLException.class).extracting("SQLState").isEqualTo("23505");
        }
    }

    @Test
    void compositeKeyEqualityUsesBothOriginalIdentifiers() {
        assertThat(new OAuthIdentityId("google", "CaseID")).isEqualTo(new OAuthIdentityId("google", "CaseID"));
        assertThat(new OAuthIdentityId("google", "CaseID")).isNotEqualTo(new OAuthIdentityId("google", "caseid"));
        assertThat(new OAuthIdentityId("google", "CaseID")).isNotEqualTo(new OAuthIdentityId("other", "CaseID"));
    }
}
