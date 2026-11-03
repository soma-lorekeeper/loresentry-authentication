package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.application.port.out.LoginSessionStore;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.domain.SessionId;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
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
    private static final byte[] REVOKE_USER = loadScript("/redis/revoke-user-sessions.lua");
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();
    private final StringRedisTemplate redis;
    // 시간 제한을 걸려고 따로 도는 가상 스레드. ContextExecutorService 로 감싸 맡길 때의 AppLogger·span 을 새 스레드로 옮긴다
    // (LoggingConfiguration 이 AppLogger 접근자를 등록한다). 그래서 그 스레드에서 찍힌 줄도 원래 요청의 trace 를 갖는다.
    private final ExecutorService commands =
            ContextExecutorService.wrap(
                    Executors.newVirtualThreadPerTaskExecutor(),
                    () -> ContextSnapshotFactory.builder().build().captureAll());

    public RedisLoginSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Optional<Instant> replace(UUID userId, SessionId id) {
        // 세션 ID·hash·Redis 키는 인증 수단이라 이 클래스의 어느 줄에도 남기지 않는다
        AppLogger.current().info("login session replace");
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
        AppLogger.current().info("login session revoke");
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

    @Override
    public void revokeUser(UUID userId) {
        AppLogger.current().info("login session revoke for user");
        Objects.requireNonNull(userId);
        execute(
                (connection, beforeWrite) -> {
                    var key = bytes("auth:session:{login}:by-user:" + userId);
                    byte[] raw = connection.stringCommands().get(key);
                    if (raw == null) return 0L;
                    String hash = hashFrom(raw);
                    beforeWrite.run();
                    if (hash == null) {
                        // 사용자 색인을 읽지 못해 by-user 키만 지운다. 색인이 가리키던 by-id 세션은 TTL까지 남는다
                        AppLogger.current()
                                .warn(
                                        "login session index unreadable",
                                        LogField.result("by_id_revoked", false));
                    }
                    return hash == null
                            ? connection
                                    .scriptingCommands()
                                    .eval(
                                            REVOKE_USER,
                                            ReturnType.INTEGER,
                                            1,
                                            key,
                                            bytes(userId.toString()),
                                            bytes(""))
                            : connection
                                    .scriptingCommands()
                                    .eval(
                                            REVOKE_USER,
                                            ReturnType.INTEGER,
                                            2,
                                            key,
                                            bytes("auth:session:{login}:by-id:" + hash),
                                            bytes(userId.toString()),
                                            bytes(hash));
                });
    }

    private static String hashFrom(byte[] raw) {
        try {
            var node = JSON.readTree(raw);
            if (!node.isObject()
                    || !node.propertyNames().equals(Set.of("schema_version", "session_hash"))
                    || !node.get("session_hash").isString()) return null;
            String value = node.get("session_hash").asString();
            return value.matches("[0-9a-f]{64}") ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
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
            if (result == null) {
                AppLogger.current().warn("login session script returned no result");
                throw failure(PortFailure.Execution.UNKNOWN, false);
            }
            if (result == -2) {
                // 스크립트가 저장된 값이나 인자를 받아들이지 않았다(-2). 쓰기 전에 멈춘다
                AppLogger.current()
                        .warn(
                                "login session script rejected",
                                LogField.result("script_result", result),
                                LogField.errorType("INVALID_DATA"));
                throw new PortFailure(
                        PortFailure.Kind.INVALID_DATA, PortFailure.Execution.NOT_EXECUTED, false);
            }
            if (result < 0 || result > 253402300799999L) {
                // 스크립트가 정해 두지 않은 값을 돌려줬다. 실행 여부를 알 수 없다
                AppLogger.current()
                        .warn(
                                "login session script rejected",
                                LogField.result("script_result", result),
                                LogField.errorType("UNAVAILABLE"));
                throw failure(PortFailure.Execution.UNKNOWN, false);
            }
            return result;
        } catch (TimeoutException e) {
            AppLogger.current()
                    .warn(
                            "login session store call timed out",
                            LogField.param("timeout_millis", 500));
            boolean prevented = gate.compareAndSet(0, 2);
            future.cancel(true);
            throw failure(
                    prevented ? PortFailure.Execution.NOT_EXECUTED : PortFailure.Execution.UNKNOWN,
                    true);
        } catch (InterruptedException e) {
            AppLogger.current().warn("login session store call interrupted");
            boolean prevented = gate.compareAndSet(0, 2);
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw failure(
                    prevented ? PortFailure.Execution.NOT_EXECUTED : PortFailure.Execution.UNKNOWN,
                    false);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof PortFailure failure) throw failure;
            // 원인은 예외 클래스 이름과 스택 프레임만 남긴다(메시지에는 키·값이 섞일 수 있다)
            Throwable cause = e.getCause() == null ? e : e.getCause();
            AppLogger.current()
                    .warn(
                            "login session store call failed",
                            LogField.errorType(cause.getClass().getName()),
                            LogField.safeStackTrace(cause));
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
        AppLogger.current().info("login session store close");
        commands.shutdownNow();
    }
}
