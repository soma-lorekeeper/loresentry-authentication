package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.application.port.out.OAuthStateStore;
import com.loresentry.authentication.application.port.out.PortFailure;
import java.time.Duration;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * 로그인 요청을 Redis에 300초 TTL로 저장하는 어댑터다.
 *
 * <p>생성은 SET NX, 소모는 GETDEL을 사용한다. 소모 후 JSON 해석 실패는 실행 완료로, Redis 호출 실패로 결과를 받지 못한 경우는 실행 여부 불명으로
 * 보고한다.
 */
@Component
public class RedisOAuthStateStore implements OAuthStateStore {
    private final StringRedisTemplate redis;
    private final OAuthStateJson json;

    public RedisOAuthStateStore(StringRedisTemplate redis, JsonMapper mapper) {
        this.redis = redis;
        this.json = new OAuthStateJson(mapper);
    }

    public boolean create(String id, State state) {
        String encoded = json.encode(state);
        try {
            return Boolean.TRUE.equals(
                    redis.opsForValue().setIfAbsent(key(id), encoded, Duration.ofSeconds(300)));
        } catch (RuntimeException e) {
            throw unavailable();
        }
    }

    public Optional<State> find(String id) {
        String value;
        try {
            value = redis.opsForValue().get(key(id));
        } catch (RuntimeException e) {
            throw unavailable();
        }
        return Optional.ofNullable(value).map(json::decode);
    }

    public Optional<State> consume(String id) {
        String value;
        try {
            value = redis.opsForValue().getAndDelete(key(id));
        } catch (RuntimeException e) {
            throw unavailable();
        }
        try {
            return Optional.ofNullable(value).map(json::decode);
        } catch (PortFailure e) {
            throw new PortFailure(e.kind(), PortFailure.Execution.EXECUTED, false);
        }
    }

    private String key(String id) {
        return "auth:oauth:" + id;
    }

    private PortFailure unavailable() {
        return new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true);
    }
}
