package com.loresentry.authentication.adapter.out.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.application.port.out.SessionStore.Session;
import com.loresentry.authentication.support.DatabaseTestSupport;
import com.loresentry.authentication.support.TestInfrastructure;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest
class SessionStoreTest extends DatabaseTestSupport {
    @Autowired RedisSessionStore store;
    @Autowired StringRedisTemplate redis;

    Session session() {
        return new Session(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.SECONDS));
    }

    @Test
    void replacesOneUserRecordAndStoresTheSharedJsonContractWithAbsoluteExpiry() {
        var user = UUID.randomUUID();
        var first = session();
        store.replace(user, first);
        var replacement = session();
        store.replace(user, replacement);
        var key = "auth:session:" + user;
        var value = new JsonMapper().readTree(redis.opsForValue().get(key));
        assertThat(value.size()).isEqualTo(4);
        assertThat(value.get("schema_version").asInt()).isEqualTo(1);
        assertThat(value.get("sid").asString()).isEqualTo(replacement.sid().toString());
        assertThat(value.get("refresh_jti").asString())
                .isEqualTo(replacement.refreshJti().toString());
        assertThat(value.get("refresh_expires_at").asLong())
                .isEqualTo(replacement.refreshExpiresAt().getEpochSecond());
        assertThat(redis.getExpire(key)).isBetween(115L, 120L);
        assertThat(
                        store.rotate(
                                user,
                                first,
                                new Session(
                                        first.sid(), UUID.randomUUID(), first.refreshExpiresAt())))
                .isFalse();
    }

    @Test
    void independentConnectionsHaveOneRotationWinnerAndNoMissingState() throws Exception {
        var user = UUID.randomUUID();
        var old = session();
        store.replace(user, old);
        var otherFactory =
                new LettuceConnectionFactory(
                        TestInfrastructure.redisHost(), TestInfrastructure.redisPort());
        otherFactory.afterPropertiesSet();
        try (var other = new RedisSessionStore(new StringRedisTemplate(otherFactory));
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                var adapter = i % 2 == 0 ? store : other;
                results.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return adapter.rotate(
                                            user,
                                            old,
                                            new Session(
                                                    old.sid(),
                                                    UUID.randomUUID(),
                                                    old.refreshExpiresAt().plusSeconds(120)));
                                }));
            }
            gate.countDown();
            int successes = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS)) successes++;
            assertThat(successes).isEqualTo(1);
            assertThat(redis.opsForValue().get("auth:session:" + user))
                    .contains(old.sid().toString())
                    .doesNotContain(old.refreshJti().toString());
        } finally {
            otherFactory.destroy();
        }
    }

    @Test
    void staleOrExpiredLogoutPreservesNewLoginAndDeletedSessionsCannotRotate() {
        var user = UUID.randomUUID();
        var old = session();
        var current = session();
        store.replace(user, old);
        store.replace(user, current);
        var key = "auth:session:" + user;
        var saved = redis.opsForValue().get(key);
        store.revoke(user, old.sid(), old.refreshExpiresAt());
        store.revoke(user, current.sid(), Instant.EPOCH);
        assertThat(redis.opsForValue().get(key)).isEqualTo(saved);
        assertThat(
                        store.rotate(
                                user,
                                new Session(
                                        current.sid(),
                                        current.refreshJti(),
                                        current.refreshExpiresAt().plusSeconds(1)),
                                new Session(
                                        current.sid(),
                                        UUID.randomUUID(),
                                        current.refreshExpiresAt())))
                .isFalse();
        store.revoke(user, current.sid(), current.refreshExpiresAt());
        store.revoke(user, current.sid(), current.refreshExpiresAt());
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(
                        store.rotate(
                                user,
                                current,
                                new Session(
                                        current.sid(),
                                        UUID.randomUUID(),
                                        current.refreshExpiresAt())))
                .isFalse();
        assertThat(redis.hasKey(key)).isFalse();
    }

    @Test
    void malformedExpiredAndIncompatibleRecordsNeverGetMutated() {
        var user = UUID.randomUUID();
        var old = session();
        var next = new Session(old.sid(), UUID.randomUUID(), old.refreshExpiresAt());
        var key = "auth:session:" + user;
        store.replace(user, old);
        var valid = redis.opsForValue().get(key);
        for (var bad :
                List.of(
                        "broken",
                        "{}",
                        valid.replace("\"schema_version\":1", "\"schema_version\":2"),
                        valid.replace(old.refreshJti().toString(), "not-a-uuid"))) {
            redis.opsForValue().set(key, bad);
            assertThatThrownBy(() -> store.rotate(user, old, next))
                    .isInstanceOfSatisfying(
                            PortFailure.class,
                            e -> {
                                assertThat(e.kind()).isEqualTo(PortFailure.Kind.INVALID_DATA);
                                assertThat(e.execution())
                                        .isEqualTo(PortFailure.Execution.NOT_EXECUTED);
                            });
            assertThatThrownBy(() -> store.revoke(user, old.sid(), old.refreshExpiresAt()))
                    .isInstanceOf(PortFailure.class);
            assertThat(redis.opsForValue().get(key)).isEqualTo(bad);
        }
        var expired = new Session(old.sid(), old.refreshJti(), Instant.ofEpochSecond(1));
        assertThatThrownBy(() -> store.replace(user, expired)).isInstanceOf(PortFailure.class);
        redis.opsForValue()
                .set(
                        key,
                        valid.replace(Long.toString(old.refreshExpiresAt().getEpochSecond()), "1"));
        assertThat(store.rotate(user, expired, next)).isFalse();
        redis.delete(key);
        redis.opsForList().leftPush(key, "wrong-type");
        assertThatThrownBy(() -> store.rotate(user, old, next))
                .isInstanceOfSatisfying(
                        PortFailure.class,
                        e -> assertThat(e.kind()).isEqualTo(PortFailure.Kind.INVALID_DATA));
        assertThat(redis.opsForList().size(key)).isEqualTo(1);
    }

    @Test
    void distinguishesConnectionFailureFromLostEvalResponseWithoutRetry() {
        var factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RedisConnectionFailureException("secret"));
        try (var adapter = new RedisSessionStore(new StringRedisTemplate(factory))) {
            assertThatThrownBy(() -> adapter.replace(UUID.randomUUID(), session()))
                    .isInstanceOfSatisfying(
                            PortFailure.class,
                            e -> {
                                assertThat(e.execution())
                                        .isEqualTo(PortFailure.Execution.NOT_EXECUTED);
                                assertThat(e).hasMessage("UNAVAILABLE").hasNoCause();
                            });
            var connection = mock(RedisConnection.class);
            var commands = mock(RedisScriptingCommands.class);
            doReturn(connection).when(factory).getConnection();
            when(connection.scriptingCommands()).thenReturn(commands);
            when(commands.eval(
                            any(byte[].class), eq(ReturnType.INTEGER), eq(1), any(byte[][].class)))
                    .thenThrow(new QueryTimeoutException("secret"));
            assertThatThrownBy(() -> adapter.replace(UUID.randomUUID(), session()))
                    .isInstanceOfSatisfying(
                            PortFailure.class,
                            e ->
                                    assertThat(e.execution())
                                            .isEqualTo(PortFailure.Execution.UNKNOWN));
            verify(commands, times(1))
                    .eval(any(byte[].class), eq(ReturnType.INTEGER), eq(1), any(byte[][].class));
        }
    }

    @Test
    void deadlinePreventsALateConnectionFromExecutingTheCommand() throws Exception {
        var factory = mock(RedisConnectionFactory.class);
        var connection = mock(RedisConnection.class);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        when(factory.getConnection())
                .thenAnswer(
                        call -> {
                            while (true) {
                                try {
                                    release.await();
                                    break;
                                } catch (InterruptedException ignored) {
                                }
                            }
                            return connection;
                        });
        doAnswer(
                        call -> {
                            closed.countDown();
                            return null;
                        })
                .when(connection)
                .close();
        try (var adapter = new RedisSessionStore(new StringRedisTemplate(factory))) {
            assertThatThrownBy(
                            () ->
                                    adapter.revoke(
                                            UUID.randomUUID(),
                                            UUID.randomUUID(),
                                            Instant.now().plusSeconds(60)))
                    .isInstanceOfSatisfying(
                            PortFailure.class,
                            e ->
                                    assertThat(e.execution())
                                            .isEqualTo(PortFailure.Execution.NOT_EXECUTED));
            release.countDown();
            assertThat(closed.await(2, TimeUnit.SECONDS)).isTrue();
            verify(connection, never()).scriptingCommands();
        } finally {
            release.countDown();
        }
    }
}
