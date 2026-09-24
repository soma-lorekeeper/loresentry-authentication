package com.loresentry.authentication.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** Dedicated disposable services; never reads application or production connection settings. */
public final class TestInfrastructure {
    private static final GenericContainer<?> POSTGRES =
            new GenericContainer<>("postgres:18.4-alpine")
                    .withEnv("POSTGRES_DB", "auth_test")
                    .withEnv("POSTGRES_USER", "auth_test")
                    .withEnv("POSTGRES_PASSWORD", "test-only")
                    .withExposedPorts(5432)
                    .waitingFor(
                            Wait.forLogMessage(
                                    ".*database system is ready to accept connections.*\\n", 2));
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(
                            System.getenv()
                                    .getOrDefault("AUTH_TEST_REDIS_IMAGE", "redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort());

    static {
        POSTGRES.start();
        REDIS.start();
    }

    private TestInfrastructure() {}

    public static String jdbcUrl() {
        return "jdbc:postgresql://"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getMappedPort(5432)
                + "/auth_test";
    }

    public static String username() {
        return "auth_test";
    }

    public static String password() {
        return "test-only";
    }

    public static String redisHost() {
        return REDIS.getHost();
    }

    public static int redisPort() {
        return REDIS.getMappedPort(6379);
    }

    public static String redisUri() {
        return "redis://" + redisHost() + ":" + redisPort();
    }

    public static Connection connection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl(), username(), password());
    }
}
