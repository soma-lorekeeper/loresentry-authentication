package com.loresentry.authentication.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class MigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18");

    @Test
    void migrationsCreateTheSchema() throws Exception {
        var result = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .load()
                .migrate();

        assertThat(result.success).isTrue();

        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                ResultSet tables = connection.createStatement().executeQuery(
                        "select table_name from information_schema.tables "
                                + "where table_schema = 'public' order by table_name")) {
            List<String> names = new ArrayList<>();
            while (tables.next()) {
                names.add(tables.getString(1));
            }
            assertThat(names).contains("users", "auth_sessions");
        }
    }
}
