package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.adapter.out.logging.LogFlow;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 기동할 때 Redis 연결을 미리 연다(PING 한 번).
 *
 * <p>Redis 명령에는 500ms 시간 제한이 있다(로그인 세션·동의 요청·OAuth state 저장소). 연결은 처음 쓸 때 만들어지는데, 그 첫 명령이 TCP 연결과
 * handshake(HELLO, CLIENT SETINFO)에 더해 EDOT agent가 Lettuce·Netty 클래스를 처음 읽을 때 계측을 붙이는 비용까지 치른다. dev
 * 실측(2026-11-05)으로 약 2.7초라, 배포 뒤 첫 로그인·로그아웃이 시간 제한을 넘겨 503이 된다. 그 비용을 요청 전에 치른다.
 *
 * <p>모든 bean을 만든 직후, Tomcat이 요청을 받기 전에 돈다. Redis에 닿지 못해도 기동은 막지 않는다. 실패 줄은 {@link LogFlow#task}가
 * 남기고, 그때는 첫 요청이 연결한다.
 */
@Component
class RedisWarmUp implements SmartInitializingSingleton {
    private final StringRedisTemplate redis;

    RedisWarmUp(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            LogFlow.task(
                    "redis warm-up",
                    task -> redis.execute((RedisCallback<String>) connection -> connection.ping()));
        } catch (RuntimeException unavailable) {
            // 실패 줄(redis warm-up failed, 메시지 없는 스택)은 LogFlow가 남겼다. 기동은 계속한다
        }
    }
}
