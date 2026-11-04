package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.adapter.out.logging.LogFlow;
import com.loresentry.authentication.logging.LogField;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 기동할 때 Redis 연결을 미리 연다(PING).
 *
 * <p>Redis 명령과 연결 handshake(HELLO, CLIENT SETINFO)에는 500ms 시간 제한이 있다(RedisConfiguration). 처음 연결할 때는
 * EDOT agent가 Lettuce·Netty 클래스를 처음 읽으며 계측을 붙이는 비용까지 더해져 handshake가 그 시간을 넘긴다. dev 실측(2026-11-05)으로
 * 첫 시도는 약 2.7초에 실패하고, 클래스가 준비될수록 다음 시도가 빨라져 몇 번 안에 붙는다. 이 일을 요청 전에 끝내 두지 않으면 배포 뒤 첫 로그인·로그아웃이 그 실패를
 * 그대로 받아 503이 된다.
 *
 * <p>모든 bean을 만든 직후, Tomcat이 요청을 받기 전에 돈다. 붙을 때까지 {@link #ATTEMPTS}번까지 다시 시도한다(시작·종료 줄은 하나이고, 종료 줄에
 * 몇 번째에 붙었는지 남긴다). 끝내 닿지 못해도 기동은 막지 않는다. 실패 줄은 {@link LogFlow#task}가 남기고, 그때는 첫 요청이 연결한다.
 */
@Component
class RedisWarmUp implements SmartInitializingSingleton {
    /** 다시 시도하는 횟수의 상한. 시도마다 handshake 시간 제한(500ms)과 클래스 준비 시간만큼 걸린다(첫 시도 약 2.7초). */
    static final int ATTEMPTS = 5;

    private final StringRedisTemplate redis;

    RedisWarmUp(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            LogFlow.task(
                    "redis warm-up",
                    task -> {
                        RuntimeException last = null;
                        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
                            try {
                                redis.execute(
                                        (RedisCallback<String>) connection -> connection.ping());
                                task.result(LogField.result("attempts", attempt));
                                return;
                            } catch (RuntimeException failure) {
                                last = failure;
                            }
                        }
                        task.result(LogField.result("attempts", ATTEMPTS));
                        throw last;
                    });
        } catch (RuntimeException unavailable) {
            // 실패 줄(redis warm-up failed, 마지막 시도의 메시지 없는 스택)은 LogFlow가 남겼다. 기동은 계속한다
        }
    }
}
