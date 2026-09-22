package com.loresentry.authentication.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class DatabaseTestSupport {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("auth.google.client-id", () -> "test-client");
        registry.add("auth.google.client-secret", () -> "test-only-secret");
        registry.add("auth.google.redirect-uri", () -> "https://api.loresentry.com/auth/oauth/google/callback");
        registry.add("spring.data.redis.host", TestInfrastructure::redisHost);
        registry.add("spring.data.redis.port", TestInfrastructure::redisPort);
        registry.add("spring.datasource.url", TestInfrastructure::jdbcUrl);
        registry.add("spring.datasource.username", TestInfrastructure::username);
        registry.add("spring.datasource.password", TestInfrastructure::password);
        registry.add("auth.jwt.private-key-base64", () -> TestKeys.PRIVATE);
        registry.add("auth.jwt.public-key-path", () -> TestKeys.PUBLIC_FILE.toString());
        registry.add("auth.jwt.key-id", () -> TestKeys.KID);
    }
}
