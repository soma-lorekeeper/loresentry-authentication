package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.application.port.out.ConsentRequestStore;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.domain.ConsentId;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
public final class RedisConsentRequestStore implements ConsentRequestStore {
    private static final DefaultRedisScript<String> SCRIPT = new DefaultRedisScript<>();
    private static final JsonMapper JSON =
            JsonMapper.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .build();

    static {
        SCRIPT.setLocation(new ClassPathResource("redis/consent-request.lua"));
        SCRIPT.setResultType(String.class);
    }

    private final StringRedisTemplate redis;

    public RedisConsentRequestStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // 동의 요청 ID와 그 hash(Redis 키)는 로그인을 이어 갈 수 있는 값이라 이 클래스의 어느 줄에도 남기지 않는다
    public Optional<Pending> create(ConsentId id, UUID userId, UUID version) {
        AppLogger.current()
                .info("consent request create", LogField.param("terms_version_id", version));
        return execute(id, "create", userId.toString(), version.toString());
    }

    public Optional<Pending> find(ConsentId id) {
        AppLogger.current().info("consent request find");
        return execute(id, "find", "", "");
    }

    public Optional<Pending> refreshVersion(ConsentId id, UUID userId, UUID version) {
        AppLogger.current()
                .info(
                        "consent request version refresh",
                        LogField.param("terms_version_id", version));
        return execute(id, "refresh", userId.toString(), version.toString());
    }

    private Optional<Pending> execute(ConsentId id, String mode, String user, String version) {
        try {
            String raw =
                    redis.execute(
                            SCRIPT,
                            List.of("auth:consent:by-id:" + id.hash()),
                            mode,
                            user,
                            version);
            if (raw == null) return Optional.empty();
            var value = JSON.readTree(raw);
            if (!value.isObject()
                    || !value.propertyNames()
                            .equals(
                                    Set.of(
                                            "user_id",
                                            "terms_version_id",
                                            "created_at",
                                            "expires_at"))
                    || !value.get("user_id").isString()
                    || !value.get("terms_version_id").isString()
                    || !value.get("created_at").isIntegralNumber()
                    || !value.get("expires_at").isIntegralNumber())
                throw new IllegalArgumentException();
            var userId = uuid(value.get("user_id").asString());
            var termsId = uuid(value.get("terms_version_id").asString());
            long created = value.get("created_at").asLong();
            long expires = value.get("expires_at").asLong();
            if (created <= 0 || expires - created != 1800000) throw new IllegalArgumentException();
            return Optional.of(
                    new Pending(
                            userId,
                            termsId,
                            Instant.ofEpochMilli(created),
                            Instant.ofEpochMilli(expires)));
        } catch (RuntimeException error) {
            // 원인은 예외 클래스 이름과 스택 프레임만 남긴다(메시지에는 SQL·키·값이 섞일 수 있다). 이 실패는 PortFailure 로 바뀌어 원인이 사라진다
            AppLogger.current()
                    .warn(
                            "consent request store call failed",
                            LogField.errorType(error.getClass().getName()),
                            LogField.safeStackTrace(error));
            throw new PortFailure(
                    PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false);
        }
    }

    @Override
    public Consumption consume(ConsentId id, UUID userId, UUID termsVersionId) {
        AppLogger.current()
                .info(
                        "consent request consume",
                        LogField.param("terms_version_id", termsVersionId));
        try {
            var result =
                    redis.execute(
                            SCRIPT,
                            List.of("auth:consent:by-id:" + id.hash()),
                            "consume",
                            userId.toString(),
                            termsVersionId.toString());
            if (result == null) return Consumption.INVALID;
            if ("#version-mismatch".equals(result)) return Consumption.VERSION_MISMATCH;
            if ("consumed".equals(result)) return Consumption.CONSUMED;
            throw new IllegalArgumentException();
        } catch (RuntimeException error) {
            // 원인은 예외 클래스 이름과 스택 프레임만 남긴다(메시지에는 SQL·키·값이 섞일 수 있다). 이 실패는 PortFailure 로 바뀌어 원인이 사라진다
            AppLogger.current()
                    .warn(
                            "consent request store call failed",
                            LogField.errorType(error.getClass().getName()),
                            LogField.safeStackTrace(error));
            throw new PortFailure(
                    PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, false);
        }
    }

    private UUID uuid(String value) {
        var id = UUID.fromString(value);
        if (!id.toString().equals(value)) throw new IllegalArgumentException();
        return id;
    }
}
