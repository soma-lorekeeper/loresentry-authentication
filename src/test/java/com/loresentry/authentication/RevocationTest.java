package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.adapter.out.redis.RedisSessionStore;
import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.support.DatabaseTestSupport;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class RevocationTest extends DatabaseTestSupport {
    @Autowired JwtTokens jwt;
    @Autowired SessionStore store;
    @Autowired RevokeUseCase revoke;

    @Test
    void repeatRevocationPreservesNewSessionAndRejectsAt() {
        var user = UUID.randomUUID();
        var firstSid = UUID.randomUUID();
        var secondSid = UUID.randomUUID();
        var one = jwt.issue(user, firstSid);
        var two = jwt.issue(user, secondSid);
        store.replace(
                user,
                new SessionStore.Session(
                        firstSid, one.refreshJti(), one.tokens().refreshExpiresAt()));
        var second =
                new SessionStore.Session(
                        secondSid, two.refreshJti(), two.tokens().refreshExpiresAt());
        store.replace(user, second);
        revoke.revoke(one.tokens().refreshToken());
        revoke.revoke(one.tokens().refreshToken());
        assertThat(
                        store.rotate(
                                user,
                                second,
                                new SessionStore.Session(
                                        secondSid,
                                        UUID.randomUUID(),
                                        two.tokens().refreshExpiresAt())))
                .isTrue();
        assertThatThrownBy(() -> revoke.revoke(one.tokens().accessToken()))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        e ->
                                assertThat(e.reason())
                                        .isEqualTo(AuthFailure.Reason.INVALID_REFRESH_TOKEN));
    }

    @Test
    void commandDeadlineIncludesBlockedConnectionAcquisition() {
        var factory = mock(RedisConnectionFactory.class);
        var released = new CountDownLatch(1);
        when(factory.getConnection())
                .thenAnswer(
                        call -> {
                            try {
                                new CountDownLatch(1).await();
                                return null;
                            } finally {
                                released.countDown();
                            }
                        });
        var adapter = new RedisSessionStore(new StringRedisTemplate(factory));
        try {
            assertThatThrownBy(
                            () ->
                                    adapter.revoke(
                                            UUID.randomUUID(),
                                            UUID.randomUUID(),
                                            Instant.now().plusSeconds(300)))
                    .isInstanceOfSatisfying(
                            PortFailure.class,
                            e -> {
                                assertThat(e.execution())
                                        .isEqualTo(PortFailure.Execution.NOT_EXECUTED);
                                assertThat(e.retryable()).isTrue();
                            });
            assertThat(released.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        } finally {
            adapter.close();
        }
    }
}
