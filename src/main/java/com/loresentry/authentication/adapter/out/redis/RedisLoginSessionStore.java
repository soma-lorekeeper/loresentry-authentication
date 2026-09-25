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
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Shared schema-v2 indices. A failed or uncertain replacement is never replayed. */
@Component
public final class RedisLoginSessionStore implements LoginSessionStore, AutoCloseable {
    private static final byte[] SCRIPT = loadScript();
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
                        new byte[][] {
                            bytes("auth:session:{login}:by-id:" + hash),
                            bytes("auth:session:{login}:by-user:" + userId),
                            bytes(userId.toString()),
                            bytes(hash)
                        });
        return result == 0 ? Optional.empty() : Optional.of(Instant.ofEpochMilli(result));
    }

    private long execute(byte[][] values) {
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
                                                                    2,
                                                                    values);
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

    private static byte[] loadScript() {
        try (var input =
                RedisLoginSessionStore.class.getResourceAsStream("/redis/login-session.lua")) {
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
