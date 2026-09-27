package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.application.port.out.LoginSessionStore;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.domain.SessionId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Shared schema-v2 indices. A failed or uncertain replacement is never replayed. */
@Component
public final class RedisLoginSessionStore implements LoginSessionStore, AutoCloseable {
    private static final byte[] SCRIPT = loadScript("/redis/login-session.lua");
    private static final byte[] REVOKE = loadScript("/redis/revoke-session.lua");
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
    private final StringRedisTemplate redis;
    private final ExecutorService commands = Executors.newVirtualThreadPerTaskExecutor();

    public RedisLoginSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Instant> replace(UUID userId, SessionId id) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(id);
        String hash = id.hash();
        long result =
                execute(
                        (connection, beforeWrite) -> {
                            beforeWrite.run();
                            return connection
                                    .scriptingCommands()
                                    .eval(
                                            SCRIPT,
                                            ReturnType.INTEGER,
                                            2,
                                            bytes("auth:session:{login}:by-id:" + hash),
                                            bytes("auth:session:{login}:by-user:" + userId),
                                            bytes(userId.toString()),
                                            bytes(hash));
                        });
        return result == 0 ? Optional.empty() : Optional.of(Instant.ofEpochMilli(result));
    }

    @Override
    public void revoke(SessionId id) {
        Objects.requireNonNull(id);
        String hash = id.hash();
        execute(
                (connection, beforeWrite) -> {
                    var key = bytes("auth:session:{login}:by-id:" + hash);
                    byte[] raw = connection.stringCommands().get(key);
                    if (raw == null) return 0L;
                    UUID user = userFrom(raw);
                    beforeWrite.run();
                    return connection
                            .scriptingCommands()
                            .eval(
                                    REVOKE,
                                    ReturnType.INTEGER,
                                    2,
                                    key,
                                    bytes("auth:session:{login}:by-user:" + user),
                                    bytes(user.toString()),
                                    bytes(hash));
                });
    }

    private static UUID userFrom(byte[] raw) {
        try {
            var node = JSON.readTree(raw);
            if (!node.isObject()
                    || !node.propertyNames()
                            .equals(Set.of("schema_version", "user_id", "created_at"))
                    || !node.get("schema_version").isIntegralNumber()
                    || node.get("schema_version").asLong() != 2
                    || !node.get("user_id").isString()
                    || !node.get("created_at").isIntegralNumber()
                    || !node.get("created_at").canConvertToLong()
                    || node.get("created_at").asLong() <= 0
                    || node.get("created_at").asLong() > 253402300799L) throw invalid();
            String value = node.get("user_id").asString();
            var user = UUID.fromString(value);
            if (!user.toString().equals(value)) throw invalid();
            return user;
        } catch (RuntimeException e) {
            throw invalid();
        }
    }

    private static PortFailure invalid() {
        return new PortFailure(
                PortFailure.Kind.INVALID_DATA, PortFailure.Execution.NOT_EXECUTED, false);
    }

    @FunctionalInterface
    private interface Command {
        Long run(RedisConnection connection, Runnable beforeWrite);
    }

    private long execute(Command command) {
        // 0: no write; 1: write may have run; 2: cancelled before writing.
        var gate = new AtomicInteger();
        var future =
                commands.submit(
                        () ->
                                redis.execute(
                                        (RedisCallback<Long>)
                                                connection -> {
                                                    if (gate.get() == 2)
                                                        throw failure(
                                                                PortFailure.Execution.NOT_EXECUTED,
                                                                false);
                                                    return command.run(
                                                            connection,
                                                            () -> {
                                                                if (!gate.compareAndSet(0, 1))
                                                                    throw failure(
                                                                            PortFailure.Execution
                                                                                    .NOT_EXECUTED,
                                                                            false);
                                                            });
                                                }));
        try {
            Long result = future.get(500, TimeUnit.MILLISECONDS);
            if (result == null) throw failure(PortFailure.Execution.UNKNOWN, false);
            if (result == -2)
                throw new PortFailure(
                        PortFailure.Kind.INVALID_DATA, PortFailure.Execution.NOT_EXECUTED, false);
            if (result < 0 || result > 253402300799999L)
                throw failure(PortFailure.Execution.UNKNOWN, false);
            return result;
        } catch (TimeoutException e) {
            boolean prevented = gate.compareAndSet(0, 2);
            future.cancel(true);
            throw failure(
                    prevented ? PortFailure.Execution.NOT_EXECUTED : PortFailure.Execution.UNKNOWN,
                    true);
        } catch (InterruptedException e) {
            boolean prevented = gate.compareAndSet(0, 2);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(
                    prevented ? PortFailure.Execution.NOT_EXECUTED : PortFailure.Execution.UNKNOWN,
                    false);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PortFailure failure) throw failure;
            throw failure(
                    gate.get() == 1
                            ? PortFailure.Execution.UNKNOWN
                            : PortFailure.Execution.NOT_EXECUTED,
                    e.getCause() instanceof TransientDataAccessException
                            || e.getCause() instanceof RedisConnectionFailureException);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] loadScript(String path) {
        try (var input = RedisLoginSessionStore.class.getResourceAsStream(path)) {
            return Objects.requireNonNull(input, "Missing session script").readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static PortFailure failure(PortFailure.Execution execution, boolean retryable) {
        return new PortFailure(PortFailure.Kind.UNAVAILABLE, execution, retryable);
    }

    @Override
    @jakarta.annotation.PreDestroy
    public void close() {
        commands.shutdownNow();
    }
}
