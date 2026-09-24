package com.loresentry.authentication.config;

import com.loresentry.authentication.application.port.in.RefreshUseCase;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.RefreshService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TokenConfiguration {
    @Bean
    com.loresentry.authentication.application.port.in.RevokeUseCase revokeTokens(
            JwtTokens jwt, SessionStore store, java.time.Clock clock) {
        return new com.loresentry.authentication.application.service.RevokeService(
                jwt, store, clock);
    }

    @Bean
    RefreshUseCase refreshTokens(JwtTokens jwt, SessionStore store) {
        return new RefreshService(jwt, store);
    }
}
