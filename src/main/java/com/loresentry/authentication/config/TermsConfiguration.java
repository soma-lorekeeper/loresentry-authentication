package com.loresentry.authentication.config;

import com.loresentry.authentication.application.port.in.TermsQueryUseCase;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.TermsQueryService;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TermsConfiguration {
    @Bean
    TermsQueryUseCase termsQuery(
            ConsentRequestStore requests,
            TermsVersionStore versions,
            AccountStore accounts,
            Clock clock) {
        return new TermsQueryService(requests, versions, accounts, clock);
    }
}
