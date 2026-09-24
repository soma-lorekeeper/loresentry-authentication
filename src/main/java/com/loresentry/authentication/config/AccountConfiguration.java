package com.loresentry.authentication.config;

import com.loresentry.authentication.adapter.out.id.UuidUserIdGenerator;
import com.loresentry.authentication.application.port.in.AccountUseCase;
import com.loresentry.authentication.application.port.in.RegisterIdentityUseCase;
import com.loresentry.authentication.application.port.out.AccountStore;
import com.loresentry.authentication.application.port.out.UserIdGenerator;
import com.loresentry.authentication.application.service.AccountService;
import com.loresentry.authentication.application.service.RegistrationService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AccountConfiguration {
    @Bean
    public UserIdGenerator userIdGenerator() {
        return new UuidUserIdGenerator();
    }

    @Bean
    public RegisterIdentityUseCase registration(
            AccountStore accounts, UserIdGenerator ids, Clock clock) {
        return new RegistrationService(accounts, ids, clock);
    }

    @Bean
    public AccountUseCase accounts(AccountStore accounts, Clock clock) {
        return new AccountService(accounts, clock);
    }
}
