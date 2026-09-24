package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.adapter.out.redis.RedisRefreshTokenStore;
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
    @Autowired RefreshTokenStore store;
    @Autowired RevokeUseCase revoke;

    @Test
    void repeatRevocationPreservesOtherDeviceAndRejectsAt() {
        var user = UUID.randomUUID();
        var one = jwt.issue(user);
        var two = jwt.issue(user);
        store.save(one.refreshJti(), user, one.tokens().refreshExpiresAt());
        store.save(two.refreshJti(), user, two.tokens().refreshExpiresAt());
        revoke.revoke(one.tokens().refreshToken());
        revoke.revoke(one.tokens().refreshToken());
        assertThat(store.consume(one.refreshJti())).isEmpty();
        assertThat(store.consume(two.refreshJti())).contains(user);
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
        var adapter =
                new RedisRefreshTokenStore(new StringRedisTemplate(factory), Clock.systemUTC());
        try {
            assertThatThrownBy(() -> adapter.delete(UUID.randomUUID()))
                    .isInstanceOfSatisfying(
                            PortFailure.class,
                            e -> {
                                assertThat(e.execution()).isEqualTo(PortFailure.Execution.UNKNOWN);
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
