package com.loresentry.authentication.config;

import com.loresentry.authentication.adapter.out.id.UuidUserIdGenerator;
import com.loresentry.authentication.application.port.out.UserIdGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AccountConfiguration {
    @Bean public UserIdGenerator userIdGenerator() { return new UuidUserIdGenerator(); }
}
