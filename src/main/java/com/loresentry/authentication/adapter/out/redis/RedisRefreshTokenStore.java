package com.loresentry.authentication.adapter.out.redis;

import lombok.RequiredArgsConstructor;
import com.loresentry.authentication.application.port.out.*;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
public class RedisRefreshTokenStore implements RefreshTokenStore {
    private final StringRedisTemplate redis;
    private final Clock clock;
    private final java.util.concurrent.ExecutorService deletions = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
    @jakarta.annotation.PreDestroy
    public void close() { deletions.shutdownNow(); }
    public void save(UUID jti, UUID userId, Instant expiresAt) {
        long ttl = Duration.between(clock.instant(), expiresAt).toMillis();
        if (ttl <= 0) throw new PortFailure(PortFailure.Kind.INVALID_DATA, PortFailure.Execution.NOT_EXECUTED, false);
        Boolean saved = execute(c -> c.stringCommands().set(key(jti), bytes(userId.toString()), Expiration.milliseconds(ttl), SetOption.ifAbsent()));
        if (!Boolean.TRUE.equals(saved)) throw new PortFailure(PortFailure.Kind.INVALID_DATA, PortFailure.Execution.UNKNOWN, false);
    }
    public Optional<UUID> consume(UUID jti) {
        byte[] value = execute(c -> c.stringCommands().getDel(key(jti)));
        if (value == null) return Optional.empty();
        try {
            String text = new String(value, StandardCharsets.UTF_8); UUID id = UUID.fromString(text);
            if (!id.toString().equals(text)) throw new IllegalArgumentException();
            return Optional.of(id);
        } catch (IllegalArgumentException invalid) {
            throw new PortFailure(PortFailure.Kind.INVALID_DATA, PortFailure.Execution.EXECUTED, false);
        }
    }
    public void delete(UUID jti) {
        var result = deletions.submit(() -> execute(c -> c.keyCommands().del(key(jti))));
        try {
            Long count = result.get(500, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (count == null) throw new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false);
        } catch (java.util.concurrent.TimeoutException e) {
            result.cancel(true);
            throw new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true);
        } catch (InterruptedException e) {
            result.cancel(true); Thread.currentThread().interrupt();
            throw new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false);
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof PortFailure failure) throw failure;
            throw new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false);
        }
    }
    private <T> T execute(Function<RedisConnection, T> command) {
        AtomicBoolean invoked = new AtomicBoolean();
        try {
            return redis.execute((RedisCallback<T>) c -> { invoked.set(true); return command.apply(c); });
        } catch (RuntimeException failure) {
            throw new PortFailure(PortFailure.Kind.UNAVAILABLE,
                invoked.get() ? PortFailure.Execution.UNKNOWN : PortFailure.Execution.NOT_EXECUTED,
                failure instanceof TransientDataAccessException
                    || failure instanceof org.springframework.data.redis.RedisConnectionFailureException);
        }
    }
    private byte[] key(UUID jti) { return bytes("auth:refresh:" + jti); }
    private byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
