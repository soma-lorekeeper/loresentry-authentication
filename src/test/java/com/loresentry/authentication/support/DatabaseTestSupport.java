package com.loresentry.authentication.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class DatabaseTestSupport {
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestInfrastructure::jdbcUrl);
        registry.add("spring.datasource.username", TestInfrastructure::username);
        registry.add("spring.datasource.password", TestInfrastructure::password);
    }
}
