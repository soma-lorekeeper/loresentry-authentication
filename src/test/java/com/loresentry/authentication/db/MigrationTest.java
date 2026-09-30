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
        assertThat(result.migrationsExecuted).isEqualTo(5);
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
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
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
            var v3 =
                    Flyway.configure()
                            .dataSource(
                                    postgres.getJdbcUrl(),
                                    postgres.getUsername(),
                                    postgres.getPassword())
                            .schemas(schema)
                            .target("3")
                            .load();
            assertThat(v3.migrate().migrationsExecuted).isEqualTo(1);
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
            var flyway = migrations(schema);
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
            assertPublishedV0(connection);
            try (var rows = statement.executeQuery("SELECT count(*) FROM user_terms_acceptances")) {
                rows.next();
                assertThat(rows.getLong(1)).isZero();
            }
            var before =
                    statement.executeQuery(
                            "SELECT content, published_at, effective_at FROM terms_versions");
            before.next();
            var content = before.getString(1);
            var published = before.getTimestamp(2);
            var effective = before.getTimestamp(3);
            before.close();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var rows =
                    statement.executeQuery(
                            "SELECT content, published_at, effective_at FROM terms_versions")) {
                rows.next();
                assertThat(rows.getString(1)).isEqualTo(content);
                assertThat(rows.getTimestamp(2)).isEqualTo(published);
                assertThat(rows.getTimestamp(3)).isEqualTo(effective);
                assertThat(rows.next()).isFalse();
            }
        }
    }

    @Test
    void onboardingMigrationMarksOnlyExistingAccountsAsCompleted() throws Exception {
        var schema = "populated_v4_upgrade";
        Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .target("4")
                .load()
                .migrate();
        try (var connection =
                        DriverManager.getConnection(
                                postgres.getJdbcUrl(),
                                postgres.getUsername(),
                                postgres.getPassword());
                var statement = connection.createStatement()) {
            connection.setSchema(schema);
            statement.execute(
                    "INSERT INTO users VALUES ('00000000-0000-4000-8000-000000000001', 'Existing author', '2026-01-02T03:04:05.123456Z', now())");
            statement.execute(
                    "INSERT INTO users VALUES ('00000000-0000-4000-8000-000000000002', 'Other author', '2026-05-06T07:08:09Z', now())");
            assertThat(migrations(schema).migrate().migrationsExecuted).isEqualTo(1);
            try (var rows =
                    statement.executeQuery(
                            "SELECT count(*), count(*) FILTER (WHERE onboarding_completed_at = created_at) FROM users")) {
                rows.next();
                assertThat(rows.getLong(1)).isEqualTo(2);
                assertThat(rows.getLong(2)).isEqualTo(2);
            }
            statement.execute(
                    "INSERT INTO users(id, display_name, created_at, updated_at) VALUES ('00000000-0000-4000-8000-000000000003', 'New author', now(), now())");
            try (var rows =
                    statement.executeQuery(
                            "SELECT onboarding_completed_at FROM users WHERE id = '00000000-0000-4000-8000-000000000003'")) {
                rows.next();
                assertThat(rows.getTimestamp(1)).isNull();
            }
            assertThat(migrations(schema).migrate().migrationsExecuted).isZero();
        }
    }

    private void assertPublishedV0(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
                var rows =
                        statement.executeQuery(
                                "SELECT id, version, title, content, published_at = effective_at, "
                                        + "effective_at <= CURRENT_TIMESTAMP, "
                                        + "to_char(effective_at AT TIME ZONE 'Asia/Seoul', 'YYYY-MM-DD') "
                                        + "FROM terms_versions WHERE terms_type = 'SERVICE_TERMS'")) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("b226d203-1e9f-4435-8dc8-7a2dc9fcd505");
            assertThat(rows.getString(2)).isEqualTo("v0");
            assertThat(rows.getString(3)).isEqualTo("Lore Sentry 서비스 이용약관");
            var content = rows.getString(4);
            for (int article = 1; article <= 10; article++) {
                assertThat(content).contains("제" + article + "조 ");
            }
            assertThat(content)
                    .contains("https://loresentry.com/policies/privacy.html", "tmdwn0509@gmail.com")
                    .doesNotContain("PRIVACY_POLICY.md", "초안", "[최초 DB 등록 시 확정]", "**책임:**", "##")
                    .endsWith("약관 버전: v0\n시행일: " + rows.getString(7));
            assertThat(rows.getBoolean(5)).isTrue();
            assertThat(rows.getBoolean(6)).isTrue();
            assertThat(rows.next()).isFalse();
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
                .containsExactly("1", "2", "3", "4", "5");
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
            assertPublishedV0(connection);
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
                                "id",
                                "display_name",
                                "created_at",
                                "updated_at",
                                "onboarding_completed_at");
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
