package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.application.port.in.RegisterIdentityUseCase;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.*;
import com.loresentry.authentication.support.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AccountConcurrencyTest extends DatabaseTestSupport {
    @Autowired AccountStore accounts;
    @Autowired RegisterIdentityUseCase registration;
    @Autowired UserIdGenerator ids;

    @Test
    void concurrentRegistrationReturnsOneCommittedUserWithoutOrphans() throws Exception {
        var subject = "concurrent-" + UUID.randomUUID();
        var identity = new OidcClient.Identity("google", subject, "사용자", "test@example.test");
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var one =
                    executor.submit(
                            () -> {
                                gate.await();
                                return registration.register(identity);
                            });
            var two =
                    executor.submit(
                            () -> {
                                gate.await();
                                return registration.register(identity);
                            });
            gate.countDown();
            assertThat(one.get(10, TimeUnit.SECONDS).id())
                    .isEqualTo(two.get(10, TimeUnit.SECONDS).id());
        }
        try (var connection = TestInfrastructure.connection();
                var statement = connection.createStatement()) {
            try (var result =
                    statement.executeQuery(
                            "SELECT count(*) FROM users u LEFT JOIN oauth_identities i ON i.user_id=u.id WHERE i.user_id IS NULL")) {
                result.next();
                assertThat(result.getInt(1)).isZero();
            }
        }
    }

    @Test
    void identityCollisionRollsBackNewUserBeforeReturningPortFailure() {
        var now = Instant.parse("2026-09-17T00:00:00Z");
        var subject = "collision-" + UUID.randomUUID();
        var first = new User(ids.generate(), "first", now, now);
        accounts.create(first, new OAuthIdentity("google", subject, first.id(), null));
        var loser = new User(ids.generate(), "loser", now, now);
        assertThatThrownBy(
                        () ->
                                accounts.create(
                                        loser,
                                        new OAuthIdentity("google", subject, loser.id(), null)))
                .isInstanceOfSatisfying(
                        PortFailure.class,
                        e ->
                                assertThat(e.kind())
                                        .isEqualTo(PortFailure.Kind.IDENTITY_ALREADY_REGISTERED));
        assertThat(accounts.findById(loser.id())).isEmpty();
        assertThat(accounts.findByIdentity("google", subject).orElseThrow().user().id())
                .isEqualTo(first.id());
        assertThatThrownBy(
                        () ->
                                accounts.create(
                                        first,
                                        new OAuthIdentity(
                                                "google",
                                                "different-" + subject,
                                                first.id(),
                                                null)))
                .isInstanceOfSatisfying(
                        PortFailure.class,
                        e -> assertThat(e.kind()).isEqualTo(PortFailure.Kind.UNAVAILABLE));
    }
}
