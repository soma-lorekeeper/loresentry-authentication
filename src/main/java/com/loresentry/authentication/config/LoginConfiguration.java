package com.loresentry.authentication.config;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.security.SecureRandom;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class LoginConfiguration {
    @Bean OAuthRequests oauthRequests(OAuthStateStore states, OidcClient provider, Clock clock) {
        return new OAuthRequests(states, provider, clock, new SecureRandom());
    }
    @Bean LoginUseCase login(OAuthRequests requests, OidcClient provider, RegisterIdentityUseCase accounts,
                            JwtTokens jwt, RefreshTokenStore refresh) {
        return new LoginService(requests, provider, accounts, jwt, refresh);
    }
}
