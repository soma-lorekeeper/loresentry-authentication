package com.loresentry.authentication.config;

import com.loresentry.authentication.adapter.out.jwt.JwtKeys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class JwtConfiguration {
    @Bean
    public JwtKeys jwtKeys(@Value("${auth.jwt.private-key-base64}") String privateKey,
                           @Value("${auth.jwt.public-key-path}") String publicPath,
                           @Value("${auth.jwt.key-id}") String kid) {
        return JwtKeys.load(privateKey, publicPath, kid);
    }
}
