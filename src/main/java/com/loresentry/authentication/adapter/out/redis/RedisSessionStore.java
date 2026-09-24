package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.application.port.out.SessionStore;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Auth가 소유하는 사용자별 세션. EVAL 한 번으로 조건과 만료를 검사하고 변경한다. */
@Component
public final class RedisSessionStore implements SessionStore, AutoCloseable {
    private static final byte[] SCRIPT = loadScript();
    private final StringRedisTemplate redis;
    private final ExecutorService commands = Executors.newVirtualThreadPerTaskExecutor();

    public RedisSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void replace(UUID userId, Session session) {
        execute(userId, "replace", json(session), "", "", "0");
    }

    @Override
    public boolean rotate(UUID userId, Session expected, Session replacement) {
        return execute(
                        userId,
                        "rotate",
                        json(replacement),
                        expected.sid().toString(),
                        expected.refreshJti().toString(),
                        Long.toString(expected.refreshExpiresAt().getEpochSecond()))
                == 1;
    }

    @Override
    public void revoke(UUID userId, UUID sid, Instant tokenExpiresAt) {
        execute(
                userId,
                "revoke",
                "",
                sid.toString(),
                "",
                Long.toString(tokenExpiresAt.getEpochSecond()));
    }

    private long execute(UUID userId, String... arguments) {
        Objects.requireNonNull(userId);
        byte[][] values = new byte[arguments.length + 1][];
        values[0] = bytes("auth:session:" + userId);
        for (int i = 0; i < arguments.length; i++) values[i + 1] = bytes(arguments[i]);
        // 0: no command; 1: command may have run; 2: cancelled before invocation.
        var gate = new AtomicInteger();
        var future =
                commands.submit(
                        () ->
                                redis.execute(
                                        (RedisCallback<Long>)
                                                connection -> {
                                                    if (!gate.compareAndSet(0, 1))
                                                        throw failure(
                                                                PortFailure.Execution.NOT_EXECUTED,
                                                                false);
                                                    return connection
                                                            .scriptingCommands()
                                                            .eval(
                                                                    SCRIPT,
                                                                    ReturnType.INTEGER,
                                                                    1,
                                                                    values);
                                                }));
        try {
            Long result = future.get(500, TimeUnit.MILLISECONDS);
            if (result == null) throw failure(PortFailure.Execution.UNKNOWN, false);
            if (result == -2)
                throw new PortFailure(
                        PortFailure.Kind.INVALID_DATA, PortFailure.Execution.NOT_EXECUTED, false);
            if (result != 0 && result != 1) throw failure(PortFailure.Execution.UNKNOWN, false);
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

    private static String json(Session session) {
        return "{\"schema_version\":1,\"sid\":\""
                + session.sid()
                + "\",\"refresh_jti\":\""
                + session.refreshJti()
                + "\",\"refresh_expires_at\":"
                + session.refreshExpiresAt().getEpochSecond()
                + "}";
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] loadScript() {
        try (var input = RedisSessionStore.class.getResourceAsStream("/redis/session.lua")) {
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
