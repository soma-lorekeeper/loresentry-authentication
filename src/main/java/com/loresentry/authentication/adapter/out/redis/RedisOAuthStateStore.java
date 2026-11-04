package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.adapter.out.logging.LogFlow;
import com.loresentry.authentication.application.port.out.OAuthStateStore;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.logging.AppLogger;
import com.loresentry.authentication.logging.LogField;
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

    // 로그인 요청 ID(Redis 키)와 state·nonce·code_verifier는 로그인 과정의 비밀 값이라 이 클래스의 어느 줄에도 남기지 않는다
    public boolean create(String id, State state) {
        AppLogger.current().info("oauth state create");
        String encoded;
        try {
            encoded = json.encode(state);
        } catch (RuntimeException e) {
            // 저장할 값이 검증을 통과하지 못했거나 JSON으로 바꾸지 못했다. 같은 예외를 그대로 던져 응답은 바뀌지 않는다
            AppLogger.current()
                    .warn(
                            "oauth state encode failed",
                            LogField.errorType(e.getClass().getName()),
                            LogField.safeStackTrace(e));
            throw e;
        }
        try {
            // Redis 호출 하나. 결과 줄(redis call completed·failed, 걸린 시간, 실패면 예외 종류·메시지 없는 스택)은 LogFlow 가
            // 남긴다
            return Boolean.TRUE.equals(
                    LogFlow.call(
                            "redis",
                            call ->
                                    redis.opsForValue()
                                            .setIfAbsent(
                                                    key(id), encoded, Duration.ofSeconds(300))));
        } catch (RuntimeException e) {
            // 원인은 바로 앞의 redis call failed 줄에 있다. 이 실패는 PortFailure 로 바뀐다
            throw unavailable();
        }
    }

    public Optional<State> find(String id) {
        AppLogger.current().info("oauth state find");
        String value;
        try {
            // Redis 호출 하나. 결과 줄(redis call completed·failed)은 LogFlow 가 남긴다
            value = LogFlow.call("redis", call -> redis.opsForValue().get(key(id)));
        } catch (RuntimeException e) {
            // 원인은 바로 앞의 redis call failed 줄에 있다. 이 실패는 PortFailure 로 바뀐다
            throw unavailable();
        }
        try {
            return Optional.ofNullable(value).map(json::decode);
        } catch (PortFailure e) {
            // 저장된 JSON을 읽지 못했다. 값은 비밀이라 남기지 않는다
            AppLogger.current()
                    .warn(
                            "oauth state decode failed",
                            LogField.errorType(e.kind().name()),
                            LogField.safeStackTrace(e));
            throw e;
        }
    }

    public Optional<State> consume(String id) {
        AppLogger.current().info("oauth state consume");
        String value;
        try {
            // Redis 호출 하나. 결과 줄(redis call completed·failed)은 LogFlow 가 남긴다
            value = LogFlow.call("redis", call -> redis.opsForValue().getAndDelete(key(id)));
        } catch (RuntimeException e) {
            // 원인은 바로 앞의 redis call failed 줄에 있다. 이 실패는 PortFailure 로 바뀐다
            throw unavailable();
        }
        try {
            return Optional.ofNullable(value).map(json::decode);
        } catch (PortFailure e) {
            // 저장된 JSON을 읽지 못했다. GETDEL은 이미 실행돼 요청은 지워졌다. 값은 비밀이라 남기지 않는다
            AppLogger.current()
                    .warn(
                            "oauth state decode failed",
                            LogField.errorType(e.kind().name()),
                            LogField.safeStackTrace(e));
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
