package com.loresentry.authentication.config;

import com.loresentry.authentication.adapter.out.google.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class GoogleConfiguration {
    @Bean GoogleSettings googleSettings(@Value("${auth.google.client-id:}") String id,
        @Value("${auth.google.client-secret:}") String secret, @Value("${auth.google.redirect-uri:}") String redirect,
        Environment environment) {
        return GoogleSettings.validated(id, secret, redirect, environment.acceptsProfiles(Profiles.of("local")));
    }
    @Bean(destroyMethod = "close") GoogleOidcClient googleOidcClient(GoogleSettings settings, Clock clock) { return new GoogleOidcClient(settings, clock); }
}
