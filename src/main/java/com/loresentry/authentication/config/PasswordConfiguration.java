package com.loresentry.authentication.config;

import com.loresentry.authentication.application.port.in.PasswordAuthUseCase;
import com.loresentry.authentication.application.port.in.TermsLoginGate;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.PasswordAuthService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class PasswordConfiguration {
    @Bean
    PasswordAuthUseCase passwordAuth(
            AccountStore accounts,
            PasswordHasher hasher,
            UserIdGenerator userIds,
            SessionIdGenerator sessionIds,
            LoginSessionStore sessions,
            TermsLoginGate terms,
            Clock clock) {
        return new PasswordAuthService(
                accounts, hasher, userIds, sessionIds, sessions, terms, clock);
    }
}
