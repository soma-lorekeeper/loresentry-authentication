package com.loresentry.authentication;

import com.loresentry.authentication.support.TestInfrastructure;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.RepeatedTest;
import static org.assertj.core.api.Assertions.assertThat;

class InfrastructureTest {
    @RepeatedTest(2)
    void isolatedServicesCanBeUsedAndReset() throws Exception {
        try (var connection = TestInfrastructure.connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS auth_test_probe (id integer primary key)");
            try {
                statement.execute("TRUNCATE auth_test_probe");
                try (var result = statement.executeQuery("SELECT count(*) FROM auth_test_probe")) {
                    result.next();
                    assertThat(result.getInt(1)).isZero();
                }
                assertThat(statement.executeUpdate("INSERT INTO auth_test_probe VALUES (1)")).isEqualTo(1);
            } finally {
                statement.execute("DROP TABLE auth_test_probe");
            }
        }
        var client = RedisClient.create(TestInfrastructure.redisUri());
        try (var connection = client.connect()) {
            var commands = connection.sync();
            commands.flushdb();
            assertThat(commands.dbsize()).isZero();
            commands.set("test:probe", "present");
            assertThat(commands.getdel("test:probe")).isEqualTo("present");
            assertThat(commands.get("test:probe")).isNull();
        } finally {
            client.shutdown();
        }
    }
}
