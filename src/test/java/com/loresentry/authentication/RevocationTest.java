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
    @Autowired RefreshUseCase refresh;
    @Autowired StringRedisTemplate redis;
    @Autowired com.loresentry.authentication.adapter.out.jwt.JwtKeys keys;

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
    void rotatedButUnexpiredRefreshTokenRevokesTheSameSession() {
        var user = UUID.randomUUID();
        var sid = UUID.randomUUID();
        var old = jwt.issue(user, sid);
        store.replace(
                user,
                new SessionStore.Session(sid, old.refreshJti(), old.tokens().refreshExpiresAt()));
        var rotated = refresh.refresh(old.tokens().refreshToken());
        assertThat(jwt.verifyRefresh(rotated.refreshToken(), false).jti())
                .isNotEqualTo(old.refreshJti());
        revoke.revoke(old.tokens().refreshToken());
        assertThat(redis.hasKey("auth:session:" + user)).isFalse();
        revoke.revoke(old.tokens().refreshToken());
    }

    @Test
    void expiredRefreshTokenCannotRevokeRenewedSession() {
        var user = UUID.randomUUID();
        var sid = UUID.randomUUID();
        var oldJwt =
                new com.loresentry.authentication.adapter.out.jwt.RsaJwtTokens(
                        keys, Clock.offset(Clock.systemUTC(), Duration.ofDays(-15)));
        var old = oldJwt.issue(user, sid);
        var current = jwt.issue(user, sid);
        store.replace(
                user,
                new SessionStore.Session(
                        sid, current.refreshJti(), current.tokens().refreshExpiresAt()));
        var before = redis.opsForValue().get("auth:session:" + user);
        revoke.revoke(old.tokens().refreshToken());
        assertThat(redis.opsForValue().get("auth:session:" + user)).isEqualTo(before);
    }

    @Test
    void retryAfterLostDeleteResponseCannotDeleteANewLogin() {
        var user = UUID.randomUUID();
        var sid = UUID.randomUUID();
        var old = jwt.issue(user, sid);
        var claims = jwt.verifyRefresh(old.tokens().refreshToken(), false);
        var newer =
                new SessionStore.Session(UUID.randomUUID(), UUID.randomUUID(), claims.expiresAt());
        store.replace(user, new SessionStore.Session(sid, claims.jti(), claims.expiresAt()));
        var proxy = mock(SessionStore.class);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(
                        call -> {
                            store.revoke(user, sid, claims.expiresAt());
                            if (calls.getAndIncrement() == 0) {
                                store.replace(user, newer);
                                throw new PortFailure(
                                        PortFailure.Kind.UNAVAILABLE,
                                        PortFailure.Execution.UNKNOWN,
                                        true);
                            }
                            return null;
                        })
                .when(proxy)
                .revoke(user, sid, claims.expiresAt());
        new com.loresentry.authentication.application.service.RevokeService(
                        jwt, proxy, Clock.systemUTC(), () -> 0L, millis -> {})
                .revoke(old.tokens().refreshToken());
        assertThat(calls.get()).isEqualTo(2);
        assertThat(
                        store.rotate(
                                user,
                                newer,
                                new SessionStore.Session(
                                        newer.sid(), UUID.randomUUID(), newer.refreshExpiresAt())))
                .isTrue();
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
