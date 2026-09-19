package com.loresentry.authentication.config;

import com.loresentry.authentication.adapter.out.google.*;
import com.loresentry.authentication.config.properties.GoogleProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleProperties.class)
public class GoogleConfiguration {
    @Bean GoogleSettings googleSettings(GoogleProperties properties, Environment environment) {
        return GoogleSettings.validated(properties.clientId(), properties.clientSecret(), properties.redirectUri(),
                environment.acceptsProfiles(Profiles.of("local")));
    }
    @Bean(destroyMethod = "close") GoogleOidcClient googleOidcClient(GoogleSettings settings, Clock clock) { return new GoogleOidcClient(settings, clock); }
}
