package com.loresentry.authentication.config;

import com.loresentry.authentication.application.port.in.TermsLoginGate;
import com.loresentry.authentication.application.port.in.TermsQueryUseCase;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.TermsLoginService;
import com.loresentry.authentication.application.service.TermsQueryService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class TermsConfiguration {
    @Bean
    TermsLoginGate termsLogin(
            @Value("${auth.terms.enabled:false}") boolean enabled,
            TermsVersionStore versions,
            TermsAcceptanceStore acceptances,
            ConsentRequestStore requests,
            SessionIdGenerator ids,
            Clock clock) {
        return new TermsLoginService(enabled, versions, acceptances, requests, ids, clock);
    }

    @Bean
    TermsQueryUseCase termsQuery(
            ConsentRequestStore requests,
            TermsVersionStore versions,
            AccountStore accounts,
            Clock clock) {
        return new TermsQueryService(requests, versions, accounts, clock);
    }
}
