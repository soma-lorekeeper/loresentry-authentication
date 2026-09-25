package com.loresentry.authentication.adapter.out.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.adapter.out.id.SecureSessionIds;
import com.loresentry.authentication.application.port.out.PortFailure;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.json.JsonMapper;

class LoginSessionStoreTest {
    static final GenericContainer<?> server =
            new GenericContainer<>(
                            System.getenv()
                                    .getOrDefault("AUTH_TEST_REDIS_IMAGE", "redis:7.4-alpine"))
                    .withExposedPorts(6379);
    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    static RedisLoginSessionStore store;
    static final SecureSessionIds ids = new SecureSessionIds();
    static final String PREFIX = "auth:session:{login}:";

    @BeforeAll
    static void start() {
        server.start();
        factory = new LettuceConnectionFactory(server.getHost(), server.getMappedPort(6379));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        store = new RedisLoginSessionStore(redis);
        // Initialize the test driver outside the operation deadline.
        redis.hasKey("test-only-warmup");
    }

    @AfterAll
    static void close() {
        if (store != null) store.close();
        if (factory != null) factory.destroy();
        server.stop();
    }

    @Test
    void storesOnlyHashesWithEqualServerExpiryAndPreservesCollisionAndOldIndex() {
        var user = UUID.randomUUID();
        var id = ids.generate();
        var expiry = store.replace(user, id).orElseThrow();
        var idKey = PREFIX + "by-id:" + id.hash();
        var userKey = PREFIX + "by-user:" + user;
        var raw = redis.opsForValue().get(idKey);
        var record = new JsonMapper().readTree(raw);
        assertThat(record.size()).isEqualTo(3);
        assertThat(record.get("schema_version").asInt()).isEqualTo(2);
        assertThat(record.get("user_id").asString()).isEqualTo(user.toString());
        assertThat(record.get("created_at").asLong()).isPositive();
        assertThat(raw).doesNotContain(id.value());
        assertThat(redis.opsForValue().get(userKey)).contains(id.hash()).doesNotContain(id.value());
        assertThat(redis.getExpire(idKey)).isBetween(1209595L, 1209600L);
        try (var connection = factory.getConnection()) {
            Long difference =
                    connection
                            .scriptingCommands()
                            .eval(
                                    "return redis.call('PTTL',KEYS[1])-redis.call('PTTL',KEYS[2])"
                                            .getBytes(),
                                    ReturnType.INTEGER,
                                    2,
                                    idKey.getBytes(),
                                    userKey.getBytes());
            assertThat(difference).isZero();
            Long absolute =
                    connection
                            .scriptingCommands()
                            .eval(
                                    "return redis.call('PEXPIRETIME',KEYS[1])".getBytes(),
                                    ReturnType.INTEGER,
                                    1,
                                    idKey.getBytes());
            assertThat(absolute).isEqualTo(expiry.toEpochMilli());
        }
        assertThat(store.replace(user, id)).isEmpty();
        assertThat(redis.opsForValue().get(idKey)).isEqualTo(raw);
        var next = ids.generate();
        store.replace(user, next).orElseThrow();
        assertThat(redis.opsForValue().get(userKey))
                .contains(next.hash())
                .doesNotContain(id.hash());
        assertThat(redis.opsForValue().get(idKey)).isEqualTo(raw);
    }

    @Test
    void malformedDuplicateEscapedAndTimelessUserRecordsFailBeforeWriting() {
        var user = UUID.randomUUID();
        var id = ids.generate();
        var userKey = PREFIX + "by-user:" + user;
        String valid = "{\"schema_version\":2,\"session_hash\":\"" + id.hash() + "\"}";
        for (var raw :
                List.of(
                        "broken",
                        "{}",
                        valid.replace(":2", ":1"),
                        valid.replace("}", ",\"schema_version\":2}"),
                        valid.replace("}", ",\"\\u0073chema_version\":2}"),
                        valid.replace("}", ",\"extra\":1}"))) {
            redis.opsForValue().set(userKey, raw, Duration.ofMinutes(1));
            assertThatThrownBy(() -> store.replace(user, id))
                    .isInstanceOfSatisfying(
                            PortFailure.class,
                            e -> assertThat(e.kind()).isEqualTo(PortFailure.Kind.INVALID_DATA));
            assertThat(redis.hasKey(PREFIX + "by-id:" + id.hash())).isFalse();
            assertThat(redis.opsForValue().get(userKey)).isEqualTo(raw);
        }
        redis.opsForValue().set(userKey, valid);
        assertThatThrownBy(() -> store.replace(user, id)).isInstanceOf(PortFailure.class);
        redis.delete(userKey);
        redis.opsForList().leftPush(userKey, "wrong-type");
        assertThatThrownBy(() -> store.replace(user, id)).isInstanceOf(PortFailure.class);
        assertThat(redis.opsForList().size(userKey)).isEqualTo(1);
        assertThat(redis.hasKey(PREFIX + "by-id:" + id.hash())).isFalse();
    }

    @Test
    void independentLoginsLeaveExactlyOneCurrentHash() throws Exception {
        var user = UUID.randomUUID();
        var otherFactory =
                new LettuceConnectionFactory(server.getHost(), server.getMappedPort(6379));
        otherFactory.afterPropertiesSet();
        var otherRedis = new StringRedisTemplate(otherFactory);
        otherRedis.hasKey("test-only-warmup");
        try (var other = new RedisLoginSessionStore(otherRedis);
                var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var gate = new CountDownLatch(1);
            var generated =
                    java.util.stream.IntStream.range(0, 8).mapToObj(i -> ids.generate()).toList();
            List<Future<?>> tasks = new ArrayList<>();
            for (int i = 0; i < generated.size(); i++) {
                var adapter = i % 2 == 0 ? store : other;
                var id = generated.get(i);
                tasks.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return adapter.replace(user, id).orElseThrow();
                                }));
            }
            gate.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
            var index = redis.opsForValue().get(PREFIX + "by-user:" + user);
            assertThat(generated.stream().filter(id -> index.contains(id.hash())).count())
                    .isEqualTo(1);
            for (var id : generated)
                assertThat(redis.hasKey(PREFIX + "by-id:" + id.hash())).isTrue();
        } finally {
            otherFactory.destroy();
        }
    }

    @Test
    void runtimeFailureAfterFirstWriteDoesNotReportSuccessOrReplaceCurrentIndex() throws Exception {
        var user = UUID.randomUUID();
        var first = ids.generate();
        store.replace(user, first).orElseThrow();
        var next = ids.generate();
        String script;
        try (var input = getClass().getResourceAsStream("/redis/login-session.lua")) {
            script =
                    new String(input.readAllBytes(), StandardCharsets.UTF_8)
                            .replace(
                                    "redis.call('SET', KEYS[2], index, 'PXAT', expiry)",
                                    "error('injected before second write')");
        }
        final byte[] fault = script.getBytes(StandardCharsets.UTF_8);
        try (var connection = factory.getConnection()) {
            assertThatThrownBy(
                            () ->
                                    connection
                                            .scriptingCommands()
                                            .eval(
                                                    fault,
                                                    ReturnType.INTEGER,
                                                    2,
                                                    (PREFIX + "by-id:" + next.hash()).getBytes(),
                                                    (PREFIX + "by-user:" + user).getBytes(),
                                                    user.toString().getBytes(),
                                                    next.hash().getBytes()))
                    .isInstanceOf(RuntimeException.class);
        }
        assertThat(redis.hasKey(PREFIX + "by-id:" + next.hash())).isTrue();
        assertThat(redis.opsForValue().get(PREFIX + "by-user:" + user))
                .contains(first.hash())
                .doesNotContain(next.hash());
    }

    @Test
    void lostEvalResponseIsUnknownAndNeverRetried() {
        var mockFactory = mock(RedisConnectionFactory.class);
        var connection = mock(RedisConnection.class);
        var commands = mock(RedisScriptingCommands.class);
        when(mockFactory.getConnection()).thenReturn(connection);
        when(connection.scriptingCommands()).thenReturn(commands);
        when(commands.eval(any(byte[].class), eq(ReturnType.INTEGER), eq(2), any(byte[][].class)))
                .thenThrow(new QueryTimeoutException("sensitive upstream detail"));
        try (var adapter = new RedisLoginSessionStore(new StringRedisTemplate(mockFactory))) {
            assertThatThrownBy(() -> adapter.replace(UUID.randomUUID(), ids.generate()))
                    .isInstanceOfSatisfying(
                            PortFailure.class,
                            e -> {
                                assertThat(e.execution()).isEqualTo(PortFailure.Execution.UNKNOWN);
                                assertThat(e).hasMessage("UNAVAILABLE").hasNoCause();
                            });
            verify(commands, times(1))
                    .eval(any(byte[].class), eq(ReturnType.INTEGER), eq(2), any(byte[][].class));
        }
    }

    @Test
    void revocationOnlyDeletesTheSuppliedSessionInBothLoginOrders() {
        var user = UUID.randomUUID();
        var old = ids.generate();
        var next = ids.generate();
        store.replace(user, old).orElseThrow();
        store.replace(user, next).orElseThrow();
        store.revoke(old);
        store.revoke(old);
        assertThat(redis.hasKey(PREFIX + "by-id:" + old.hash())).isFalse();
        assertThat(redis.opsForValue().get(PREFIX + "by-user:" + user)).contains(next.hash());
        store.revoke(next);
        assertThat(redis.hasKey(PREFIX + "by-id:" + next.hash())).isFalse();
        assertThat(redis.hasKey(PREFIX + "by-user:" + user)).isFalse();
        store.replace(user, old).orElseThrow();
        store.revoke(old);
        store.replace(user, next).orElseThrow();
        store.revoke(old);
        assertThat(redis.opsForValue().get(PREFIX + "by-user:" + user)).contains(next.hash());
        store.revoke(ids.generate());
    }

    @Test
    void corruptOrMismatchedExpiryCannotBeReportedAsRevoked() {
        var user = UUID.randomUUID();
        var id = ids.generate();
        store.replace(user, id).orElseThrow();
        var key = PREFIX + "by-id:" + id.hash();
        var index = PREFIX + "by-user:" + user;
        redis.expire(key, Duration.ofSeconds(30));
        assertThatThrownBy(() -> store.revoke(id)).isInstanceOf(PortFailure.class);
        assertThat(redis.hasKey(key)).isTrue();
        assertThat(redis.hasKey(index)).isTrue();
        redis.opsForValue()
                .set(
                        key,
                        "{\"schema_version\":2,\"schema_version\":2,\"user_id\":\""
                                + user
                                + "\",\"created_at\":1}",
                        Duration.ofSeconds(30));
        assertThatThrownBy(() -> store.revoke(id)).isInstanceOf(PortFailure.class);
        assertThat(redis.hasKey(index)).isTrue();
        redis.delete(key);
        store.revoke(id);
        assertThat(redis.hasKey(index)).isTrue();
    }

    @Test
    void aGetFinishingAfterTheDeadlineCannotStartRevocation() throws Exception {
        var mockFactory = mock(RedisConnectionFactory.class);
        var connection = mock(RedisConnection.class);
        var strings = mock(RedisStringCommands.class);
        var release = new CountDownLatch(1);
        var closed = new CountDownLatch(1);
        var user = UUID.randomUUID();
        when(mockFactory.getConnection()).thenReturn(connection);
        when(connection.stringCommands()).thenReturn(strings);
        when(strings.get(any()))
                .thenAnswer(
                        call -> {
                            while (true) {
                                try {
                                    release.await();
                                    break;
                                } catch (InterruptedException ignored) {
                                }
                            }
                            return ("{\"schema_version\":2,\"user_id\":\""
                                            + user
                                            + "\",\"created_at\":1}")
                                    .getBytes();
                        });
        doAnswer(
                        call -> {
                            closed.countDown();
                            return null;
                        })
                .when(connection)
                .close();
        try (var adapter = new RedisLoginSessionStore(new StringRedisTemplate(mockFactory))) {
            assertThatThrownBy(() -> adapter.revoke(ids.generate()))
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
