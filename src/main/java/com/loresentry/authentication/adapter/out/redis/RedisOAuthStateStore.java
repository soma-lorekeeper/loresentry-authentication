package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.application.port.out.OAuthStateStore;
import com.loresentry.authentication.application.port.out.PortFailure;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.time.Duration;
import java.util.Optional;

@Component
public class RedisOAuthStateStore implements OAuthStateStore {
    private final StringRedisTemplate redis;
    private final OAuthStateJson json;
    public RedisOAuthStateStore(StringRedisTemplate redis, JsonMapper mapper) { this.redis = redis; this.json = new OAuthStateJson(mapper); }
    public boolean create(String id, State state) {
        String encoded = json.encode(state);
        try { return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key(id), encoded, Duration.ofSeconds(300))); }
        catch (RuntimeException e) { throw unavailable(); }
    }
    public Optional<State> find(String id) {
        String value;
        try { value = redis.opsForValue().get(key(id)); }
        catch (RuntimeException e) { throw unavailable(); }
        return Optional.ofNullable(value).map(json::decode);
    }
    public Optional<State> consume(String id) {
        String value;
        try { value = redis.opsForValue().getAndDelete(key(id)); }
        catch (RuntimeException e) { throw unavailable(); }
        return Optional.ofNullable(value).map(json::decode);
    }
    private String key(String id) { return "auth:oauth:" + id; }
    private PortFailure unavailable() { return new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true); }
}
