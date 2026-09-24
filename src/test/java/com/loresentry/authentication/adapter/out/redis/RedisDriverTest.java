package com.loresentry.authentication.adapter.out.redis;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.support.DatabaseTestSupport;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.protocol.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@SpringBootTest
class RedisDriverTest extends DatabaseTestSupport {
    @Autowired LettuceConnectionFactory connections;

    @Test
    void productionDriverRejectsDisconnectedCommandsAndNeverReplays() {
        var config = connections.getClientConfiguration();
        assertThat(config.getCommandTimeout()).isEqualTo(Duration.ofMillis(500));
        var options = config.getClientOptions().orElseThrow();
        assertThat(options.getSocketOptions().getConnectTimeout())
                .isEqualTo(Duration.ofMillis(500));
        assertThat(options.getDisconnectedBehavior())
                .isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        for (var type :
                List.of(CommandType.EVAL, CommandType.GETDEL, CommandType.SET, CommandType.DEL)) {
            var command = mock(RedisCommand.class);
            when(command.getType()).thenReturn(type);
            assertThat(options.getReplayFilter().test(command)).isTrue();
        }
    }
}
