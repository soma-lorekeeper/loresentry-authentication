package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.application.port.out.ConsentRequestStore;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.domain.ConsentId;
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

    public Optional<Pending> create(ConsentId id, UUID userId, UUID version) {
        return execute(id, "create", userId.toString(), version.toString());
    }

    public Optional<Pending> find(ConsentId id) {
        return execute(id, "find", "", "");
    }

    public Optional<Pending> refreshVersion(ConsentId id, UUID userId, UUID version) {
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
