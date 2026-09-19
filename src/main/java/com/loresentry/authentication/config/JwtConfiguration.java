package com.loresentry.authentication.config;

import com.loresentry.authentication.adapter.out.jwt.JwtKeys;
import com.loresentry.authentication.adapter.out.jwt.RsaJwtTokens;
import com.loresentry.authentication.application.port.out.JwtTokens;
import com.loresentry.authentication.config.properties.JwtProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {
    @Bean public JwtTokens jwtTokens(JwtKeys keys, Clock clock) { return new RsaJwtTokens(keys, clock); }
    @Bean
    public JwtKeys jwtKeys(JwtProperties properties) {
        return JwtKeys.load(properties.privateKeyBase64(), properties.publicKeyPath(), properties.keyId());
    }
}
