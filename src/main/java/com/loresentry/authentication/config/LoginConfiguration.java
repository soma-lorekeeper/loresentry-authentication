package com.loresentry.authentication.config;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.*;
import java.security.SecureRandom;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 포트 구현체를 로그인 서비스에 연결하고, 생성한 객체를 Spring 빈으로 등록한다. */
@Configuration(proxyBeanMethods = false)
public class LoginConfiguration {
    @Bean
    OAuthRequests oauthRequests(OAuthStateStore states, OidcClient provider, Clock clock) {
        return new OAuthRequests(states, provider, clock, new SecureRandom());
    }

    @Bean
    LoginUseCase login(
            OAuthRequests requests,
            OidcClient provider,
            RegisterIdentityUseCase accounts,
            JwtTokens jwt,
            RefreshTokenStore refresh) {
        return new LoginService(requests, provider, accounts, jwt, refresh);
    }
}
