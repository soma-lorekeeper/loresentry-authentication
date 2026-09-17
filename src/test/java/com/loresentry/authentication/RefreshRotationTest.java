package com.loresentry.authentication;

import com.loresentry.authentication.adapter.out.jwt.*;
import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.RefreshService;
import com.loresentry.authentication.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class RefreshRotationTest extends DatabaseTestSupport {
    @Autowired JwtKeys keys;
    @Autowired JwtTokens jwt;
    @Autowired RefreshTokenStore store;
    @Autowired RefreshUseCase service;
    @Autowired StringRedisTemplate redis;
    @Test void concurrentRotationHasOneWinnerAndDoesNotAffectAnotherDevice() throws Exception {
        UUID user = UUID.randomUUID(); var first = jwt.issue(user); var second = jwt.issue(user);
        store.save(first.refreshJti(), user, first.tokens().refreshExpiresAt());
        store.save(second.refreshJti(), user, second.tokens().refreshExpiresAt());
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Optional<TokenPair>>> results = new ArrayList<>();
            for (int i = 0; i < 6; i++) results.add(executor.submit(() -> {
                gate.await();
                try { return Optional.of(service.refresh(first.tokens().refreshToken())); }
                catch (AuthFailure failure) { assertThat(failure.reason()).isEqualTo(AuthFailure.Reason.REFRESH_REJECTED); return Optional.empty(); }
            }));
            gate.countDown(); int successes = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS).isPresent()) successes++;
            assertThat(successes).isEqualTo(1);
        }
        // Simulate losing the successful response: the old RT can never recover that result.
        assertThatThrownBy(() -> service.refresh(first.tokens().refreshToken())).isInstanceOf(AuthFailure.class);
        assertThat(redis.hasKey("auth:refresh:" + second.refreshJti())).isTrue();
        assertThat(service.refresh(second.tokens().refreshToken())).isNotNull();
    }
    @Test void newExpiryRollsForwardAndStorageLossRejectsOldToken() {
        var user = UUID.randomUUID(); var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var oldJwt = new RsaJwtTokens(keys, Clock.fixed(now.minusSeconds(86400), ZoneOffset.UTC));
        var old = oldJwt.issue(user); store.save(old.refreshJti(), user, old.tokens().refreshExpiresAt());
        var fixedJwt = new RsaJwtTokens(keys, Clock.fixed(now, ZoneOffset.UTC));
        var fresh = new RefreshService(fixedJwt, store).refresh(old.tokens().refreshToken());
        assertThat(fresh.refreshExpiresAt()).isEqualTo(now.plusSeconds(14 * 86400));
        var claims = fixedJwt.verifyRefresh(fresh.refreshToken(), false);
        assertThat(redis.getExpire("auth:refresh:" + claims.jti())).isBetween(14L * 86400 - 5, 14L * 86400);
        store.delete(claims.jti());
        assertThatThrownBy(() -> service.refresh(fresh.refreshToken())).isInstanceOfSatisfying(AuthFailure.class,
            e -> assertThat(e.reason()).isEqualTo(AuthFailure.Reason.REFRESH_REJECTED));
    }
}
