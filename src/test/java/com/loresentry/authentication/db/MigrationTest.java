package com.loresentry.authentication.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class MigrationTest {

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4-alpine");

    @Test
    void freshDatabaseMigratesToTheCurrentAccountSchema() throws Exception {
        var flyway = migrations("fresh_install");
        var result = flyway.migrate();
        assertThat(result.success).isTrue();
        assertThat(result.migrationsExecuted).isEqualTo(3);
        assertCurrentSchema(flyway, "fresh_install");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void deployedEmptyV1UpgradesWithoutRewritingItsHistory() throws Exception {
        var schema = "empty_v1_upgrade";
        var v1 =
                Flyway.configure()
                        .dataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword())
                        .schemas(schema)
                        .target("1")
                        .load();
        assertThat(v1.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(tables(schema))
                .containsExactlyInAnyOrder("flyway_schema_history", "users", "auth_sessions");
        var checksum = v1.info().current().getChecksum();

        var flyway = migrations(schema);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        assertThat(flyway.info().applied())
                .filteredOn(
                        migration ->
                                MigrationVersion.fromVersion("1").equals(migration.getVersion()))
                .extracting(MigrationInfo::getChecksum)
                .containsExactly(checksum);
        assertCurrentSchema(flyway, schema);
    }

    @Test
    void termsMigrationPreservesExistingAccountsAndDoesNotPublishDrafts() throws Exception {
        var schema = "populated_v2_upgrade";
        var previous =
                Flyway.configure()
                        .dataSource(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword())
                        .schemas(schema)
                        .target("2")
                        .load();
        previous.migrate();
        try (var connection =
                DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setSchema(schema);
            var statement = connection.createStatement();
            statement.execute(
                    "INSERT INTO users VALUES ('00000000-0000-4000-8000-000000000001', 'Existing author', now(), now())");
            statement.execute(
                    "INSERT INTO oauth_identities VALUES ('GOOGLE', 'existing-subject', '00000000-0000-4000-8000-000000000001', 'author@example.test')");
            assertThat(migrations(schema).migrate().migrationsExecuted).isEqualTo(1);
            try (var rows =
                    statement.executeQuery(
                            "SELECT display_name, email FROM users JOIN oauth_identities ON users.id=oauth_identities.user_id")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("Existing author");
                assertThat(rows.getString(2)).isEqualTo("author@example.test");
            }
            for (var table : List.of("terms_versions", "user_terms_acceptances")) {
                try (var rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
                    rows.next();
                    assertThat(rows.getLong(1)).isZero();
                }
            }
        }
    }

    private Flyway migrations(String schema) {
        return Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .load();
    }

    private void assertCurrentSchema(Flyway flyway, String schema) throws Exception {
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.info().applied())
                .filteredOn(migration -> migration.getVersion() != null)
                .extracting(migration -> migration.getVersion().getVersion())
                .containsExactly("1", "2", "3");
        assertThat(tables(schema))
                .containsExactlyInAnyOrder(
                        "flyway_schema_history",
                        "users",
                        "oauth_identities",
                        "terms_versions",
                        "user_terms_acceptances");
        try (Connection connection =
                DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setSchema(schema);
            try (var columns =
                    connection
                            .createStatement()
                            .executeQuery(
                                    "select column_name from information_schema.columns "
                                            + "where table_schema = current_schema() and table_name = 'users'")) {
                List<String> names = new ArrayList<>();
                while (columns.next()) names.add(columns.getString(1));
                assertThat(names)
                        .containsExactlyInAnyOrder(
                                "id", "display_name", "created_at", "updated_at");
            }
        }
    }

    private List<String> tables(String schema) throws Exception {
        try (Connection connection =
                DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())) {
            connection.setSchema(schema);
            try (var tables =
                    connection
                            .createStatement()
                            .executeQuery(
                                    "select table_name from information_schema.tables where table_schema = current_schema()")) {
                List<String> names = new ArrayList<>();
                while (tables.next()) names.add(tables.getString(1));
                return names;
            }
        }
    }
}
