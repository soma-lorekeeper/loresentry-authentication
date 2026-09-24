package com.loresentry.authentication.adapter.out.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.OAuthRequests;
import com.loresentry.authentication.domain.OAuthSecrets;
import com.loresentry.authentication.support.DatabaseTestSupport;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class OAuthConsumptionTest extends DatabaseTestSupport {
    @Autowired RedisOAuthStateStore store;
    @Autowired StringRedisTemplate redis;
    final SecureRandom random = new SecureRandom();
    final Instant now = Instant.parse("2026-09-17T00:00:00Z");
    final OidcClient.Settings settings =
            new OidcClient.Settings("google", "client", "https://callback");

    OAuthStateStore.State state() {
        return new OAuthStateStore.State(
                1,
                "google",
                "client",
                "https://callback",
                OAuthSecrets.generate(random),
                OAuthSecrets.generate(random),
                OAuthSecrets.generate(random),
                now,
                now.plusSeconds(300));
    }

    OAuthRequests service(OAuthStateStore target, Instant time) {
        var provider = mock(OidcClient.class);
        when(provider.settings()).thenReturn(settings);
        return new OAuthRequests(target, provider, Clock.fixed(time, ZoneOffset.UTC), random);
    }

    LoginUseCase.Callback callback(String id, String state) {
        return new LoginUseCase.Callback(id, state, "code", null);
    }

    @Test
    void invalidStateAndExpiryDoNotConsumeAndCancelDoesConsume() {
        var value = state();
        var id = OAuthSecrets.generate(random);
        store.create(id, value);
        assertFailure(
                () -> service(store, now).consume(callback(id, OAuthSecrets.generate(random))),
                AuthFailure.Consumption.NOT_CONSUMED);
        assertFailure(
                () -> service(store, now.plusSeconds(300)).consume(callback(id, value.state())),
                AuthFailure.Consumption.NOT_CONSUMED);
        assertThat(store.find(id)).contains(value);
        assertThat(
                        service(store, now)
                                .consume(
                                        new LoginUseCase.Callback(
                                                id, value.state(), null, "access_denied")))
                .isEqualTo(value);
        assertThat(store.find(id)).isEmpty();
        assertFailure(
                () -> service(store, now).consume(callback(id, value.state())),
                AuthFailure.Consumption.NOT_CONSUMED);
        var expiring = OAuthSecrets.generate(random);
        store.create(expiring, value);
        redis.expire("auth:oauth:" + expiring, Duration.ZERO);
        assertFailure(
                () -> service(store, now).consume(callback(expiring, value.state())),
                AuthFailure.Consumption.NOT_CONSUMED);
    }

    @Test
    void concurrentCallbacksConsumeExactlyOnce() throws Exception {
        var value = state();
        var id = OAuthSecrets.generate(random);
        store.create(id, value);
        var service = service(store, now);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++)
                results.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    try {
                                        service.consume(callback(id, value.state()));
                                        return true;
                                    } catch (AuthFailure e) {
                                        assertThat(e.consumption())
                                                .isEqualTo(AuthFailure.Consumption.NOT_CONSUMED);
                                        return false;
                                    }
                                }));
            gate.countDown();
            int success = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) success++;
            assertThat(success).isEqualTo(1);
        }
    }

    @Test
    void configMismatchDoesNotConsumeAndReturnedValueIsRevalidated() {
        var value = state();
        for (var invalid :
                List.of(
                        new OAuthStateStore.State(
                                1,
                                "other",
                                "client",
                                "https://callback",
                                value.state(),
                                value.nonce(),
                                value.codeVerifier(),
                                now,
                                now.plusSeconds(300)),
                        new OAuthStateStore.State(
                                1,
                                "google",
                                "other",
                                "https://callback",
                                value.state(),
                                value.nonce(),
                                value.codeVerifier(),
                                now,
                                now.plusSeconds(300)),
                        new OAuthStateStore.State(
                                1,
                                "google",
                                "client",
                                "https://other",
                                value.state(),
                                value.nonce(),
                                value.codeVerifier(),
                                now,
                                now.plusSeconds(300)))) {
            var target = mock(OAuthStateStore.class);
            var id = OAuthSecrets.generate(random);
            when(target.find(id)).thenReturn(Optional.of(invalid));
            assertFailure(
                    () -> service(target, now).consume(callback(id, value.state())),
                    AuthFailure.Consumption.NOT_CONSUMED);
            verify(target, never()).consume(anyString());
            when(target.find(id)).thenReturn(Optional.of(value));
            when(target.consume(id)).thenReturn(Optional.of(invalid));
            assertFailure(
                    () -> service(target, now).consume(callback(id, value.state())),
                    AuthFailure.Consumption.CONSUMED);
        }
    }

    @Test
    void failuresPreserveCommandOutcome() {
        for (var execution : PortFailure.Execution.values()) {
            var target = mock(OAuthStateStore.class);
            var value = state();
            var id = OAuthSecrets.generate(random);
            when(target.find(id)).thenReturn(Optional.of(value));
            when(target.consume(id))
                    .thenThrow(new PortFailure(PortFailure.Kind.UNAVAILABLE, execution, true));
            var expected =
                    switch (execution) {
                        case NOT_EXECUTED -> AuthFailure.Consumption.NOT_CONSUMED;
                        case EXECUTED -> AuthFailure.Consumption.CONSUMED;
                        case UNKNOWN -> AuthFailure.Consumption.UNKNOWN;
                    };
            assertFailure(
                    () -> service(target, now).consume(callback(id, value.state())), expected);
            verify(target, times(1)).consume(id);
            verify(target, never()).create(any(), any());
        }
    }

    @Test
    void malformedConsumedJsonStillReportsExecuted() {
        var id = OAuthSecrets.generate(random);
        redis.opsForValue().set("auth:oauth:" + id, "broken");
        assertThatThrownBy(() -> store.consume(id))
                .isInstanceOfSatisfying(
                        PortFailure.class,
                        e -> assertThat(e.execution()).isEqualTo(PortFailure.Execution.EXECUTED));
        assertThat(redis.hasKey("auth:oauth:" + id)).isFalse();
    }

    void assertFailure(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable task,
            AuthFailure.Consumption expected) {
        assertThatThrownBy(task)
                .isInstanceOfSatisfying(
                        AuthFailure.class, e -> assertThat(e.consumption()).isEqualTo(expected));
    }
}
