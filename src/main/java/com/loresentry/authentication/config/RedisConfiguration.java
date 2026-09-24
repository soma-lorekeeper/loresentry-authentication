package com.loresentry.authentication.config;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import java.time.Duration;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedisConfiguration {
    @Bean
    LettuceClientConfigurationBuilderCustomizer authRedisLimits() {
        return builder ->
                builder.commandTimeout(Duration.ofMillis(500))
                        .clientOptions(
                                ClientOptions.builder()
                                        .autoReconnect(true)
                                        .disconnectedBehavior(
                                                ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                                        .replayFilter(command -> true)
                                        .socketOptions(
                                                SocketOptions.builder()
                                                        .connectTimeout(Duration.ofMillis(500))
                                                        .build())
                                        .build());
    }
}
