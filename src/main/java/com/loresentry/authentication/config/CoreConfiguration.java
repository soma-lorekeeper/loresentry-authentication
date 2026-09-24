package com.loresentry.authentication.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class CoreConfiguration {
    @Bean
    public Clock authClock() {
        return Clock.systemUTC();
    }
}
