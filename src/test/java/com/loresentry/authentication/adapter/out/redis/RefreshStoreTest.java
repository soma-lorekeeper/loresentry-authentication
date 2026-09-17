package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.support.DatabaseTestSupport;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.protocol.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class RefreshStoreTest extends DatabaseTestSupport {
    @Autowired RedisRefreshTokenStore store;
    @Autowired StringRedisTemplate redis;
    @Autowired LettuceConnectionFactory connections;
    @Test void storesOnlyUserWithRemainingTtlAndOneConcurrentConsumer() throws Exception {
        var jti = UUID.randomUUID(); var user = UUID.randomUUID();
        store.save(jti, user, Instant.now().plusSeconds(40));
        assertThat(redis.opsForValue().get("auth:refresh:" + jti)).isEqualTo(user.toString());
        assertThat(redis.getExpire("auth:refresh:" + jti)).isBetween(35L, 40L);
        assertThatThrownBy(() -> store.save(jti, UUID.randomUUID(), Instant.now().plusSeconds(40))).isInstanceOf(PortFailure.class);
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Optional<UUID>>> results = new ArrayList<>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> { gate.await(); return store.consume(jti); }));
            gate.countDown(); int count = 0;
            for (var result : results) if (result.get(5, TimeUnit.SECONDS).isPresent()) count++;
            assertThat(count).isEqualTo(1);
        }
        assertThat(store.consume(jti)).isEmpty(); store.delete(jti); store.delete(jti);
    }
    @Test void rejectsExpiredAndMalformedStoredId() {
        var jti = UUID.randomUUID();
        assertThatThrownBy(() -> store.save(jti, UUID.randomUUID(), Instant.EPOCH)).isInstanceOfSatisfying(PortFailure.class,
            e -> assertThat(e.execution()).isEqualTo(PortFailure.Execution.NOT_EXECUTED));
        redis.opsForValue().set("auth:refresh:" + jti, "not-a-uuid");
        assertThatThrownBy(() -> store.consume(jti)).isInstanceOfSatisfying(PortFailure.class,
            e -> assertThat(e.execution()).isEqualTo(PortFailure.Execution.EXECUTED));
    }
    @Test void distinguishesConnectionAcquisitionFromLostCommandResponse() {
        var factory = mock(RedisConnectionFactory.class);
        when(factory.getConnection()).thenThrow(new RedisConnectionFailureException("secret"));
        var adapter = new RedisRefreshTokenStore(new StringRedisTemplate(factory), Clock.systemUTC());
        assertThatThrownBy(() -> adapter.consume(UUID.randomUUID())).isInstanceOfSatisfying(PortFailure.class, e -> {
            assertThat(e.execution()).isEqualTo(PortFailure.Execution.NOT_EXECUTED); assertThat(e.retryable()).isTrue();
            assertThat(e).hasMessage("UNAVAILABLE").hasNoCause();
        });
        var connection = mock(RedisConnection.class); var commands = mock(RedisStringCommands.class);
        doReturn(connection).when(factory).getConnection(); when(connection.stringCommands()).thenReturn(commands);
        when(commands.getDel(any())).thenThrow(new QueryTimeoutException("token secret"));
        assertThatThrownBy(() -> adapter.consume(UUID.randomUUID())).isInstanceOfSatisfying(PortFailure.class,
            e -> assertThat(e.execution()).isEqualTo(PortFailure.Execution.UNKNOWN));
        verify(commands, times(1)).getDel(any());
    }
    @Test void productionDriverRejectsDisconnectedCommandsAndNeverReplays() {
        var config = connections.getClientConfiguration();
        assertThat(config.getCommandTimeout()).isEqualTo(Duration.ofMillis(500));
        var options = config.getClientOptions().orElseThrow();
        assertThat(options.getSocketOptions().getConnectTimeout()).isEqualTo(Duration.ofMillis(500));
        assertThat(options.getDisconnectedBehavior()).isEqualTo(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS);
        for (var type : List.of(CommandType.GETDEL, CommandType.SET, CommandType.DEL)) {
            var command = mock(RedisCommand.class); when(command.getType()).thenReturn(type);
            assertThat(options.getReplayFilter().test(command)).isTrue();
        }
    }
}
