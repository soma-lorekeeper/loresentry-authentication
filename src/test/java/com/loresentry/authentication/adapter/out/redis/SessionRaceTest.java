package com.loresentry.authentication.adapter.out.redis;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.application.port.out.SessionStore.Session;
import com.loresentry.authentication.support.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class SessionRaceTest extends DatabaseTestSupport {
    @Autowired RedisSessionStore first;
    @Autowired StringRedisTemplate redis;

    Session session() {
        return new Session(
                UUID.randomUUID(),
                UUID.randomUUID(),
                Instant.now().plusSeconds(300).truncatedTo(ChronoUnit.SECONDS));
    }

    LettuceConnectionFactory connection() {
        var factory =
                new LettuceConnectionFactory(
                        TestInfrastructure.redisHost(), TestInfrastructure.redisPort());
        factory.afterPropertiesSet();
        return factory;
    }

    @ParameterizedTest
    @CsvSource({
        "login,refresh,false,true",
        "refresh,login,true,true",
        "login,revoke,false,true",
        "revoke,login,false,true",
        "refresh,revoke,true,false",
        "revoke,refresh,false,false"
    })
    void orderedCompetingCommandsAcrossInstancesPreserveTheLatestSession(
            String firstOperation, String secondOperation, boolean refreshed, boolean present)
            throws Exception {
        var user = UUID.randomUUID();
        var old = session();
        var newer = session();
        var rotated =
                new Session(old.sid(), UUID.randomUUID(), old.refreshExpiresAt().plusSeconds(300));
        first.replace(user, old);
        var factory = connection();
        try (var second = new RedisSessionStore(new StringRedisTemplate(factory));
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var start = new CountDownLatch(1);
            var firstDone = new CountDownLatch(1);
            var refreshResult = new AtomicBoolean();
            var one =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                                try {
                                    apply(
                                            first,
                                            firstOperation,
                                            user,
                                            old,
                                            newer,
                                            rotated,
                                            refreshResult);
                                } finally {
                                    firstDone.countDown();
                                }
                                return null;
                            });
            var two =
                    workers.submit(
                            () -> {
                                ready.countDown();
                                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                                assertThat(firstDone.await(5, TimeUnit.SECONDS)).isTrue();
                                apply(
                                        second,
                                        secondOperation,
                                        user,
                                        old,
                                        newer,
                                        rotated,
                                        refreshResult);
                                return null;
                            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            one.get(5, TimeUnit.SECONDS);
            two.get(5, TimeUnit.SECONDS);
            assertThat(refreshResult.get()).isEqualTo(refreshed);
            var saved = redis.opsForValue().get("auth:session:" + user);
            if (present)
                assertThat(saved).contains(newer.sid().toString(), newer.refreshJti().toString());
            else assertThat(saved).isNull();
        } finally {
            factory.destroy();
        }
    }

    void apply(
            RedisSessionStore store,
            String operation,
            UUID user,
            Session old,
            Session newer,
            Session rotated,
            AtomicBoolean result) {
        switch (operation) {
            case "login" -> store.replace(user, newer);
            case "refresh" -> result.set(store.rotate(user, old, rotated));
            case "revoke" -> store.revoke(user, old.sid(), old.refreshExpiresAt());
            default -> throw new AssertionError(operation);
        }
    }

    @Test
    void concurrentLoginsLeaveExactlyOneUsableSessionAcrossInstances() throws Exception {
        var user = UUID.randomUUID();
        var one = session();
        var two = session();
        var factory = connection();
        try (var second = new RedisSessionStore(new StringRedisTemplate(factory));
                var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CyclicBarrier(2);
            var a =
                    workers.submit(
                            () -> {
                                ready.await(5, TimeUnit.SECONDS);
                                first.replace(user, one);
                                return null;
                            });
            var b =
                    workers.submit(
                            () -> {
                                ready.await(5, TimeUnit.SECONDS);
                                second.replace(user, two);
                                return null;
                            });
            a.get(5, TimeUnit.SECONDS);
            b.get(5, TimeUnit.SECONDS);
            var aWorks =
                    first.rotate(
                            user,
                            one,
                            new Session(one.sid(), UUID.randomUUID(), one.refreshExpiresAt()));
            var bWorks =
                    second.rotate(
                            user,
                            two,
                            new Session(two.sid(), UUID.randomUUID(), two.refreshExpiresAt()));
            assertThat(aWorks ^ bWorks).isTrue();
        } finally {
            factory.destroy();
        }
    }

    @Test
    void readOnlyObserverNeverSeesAGapDuringRepeatedRotations() throws Exception {
        var user = UUID.randomUUID();
        var old = session();
        first.replace(user, old);
        var factory = connection();
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            var reader = new StringRedisTemplate(factory);
            var observing = new CountDownLatch(1);
            var done = new AtomicBoolean();
            var reads =
                    workers.submit(
                            () -> {
                                int count = 0;
                                while (!done.get()) {
                                    assertThat(reader.opsForValue().get("auth:session:" + user))
                                            .contains(old.sid().toString());
                                    count++;
                                    observing.countDown();
                                }
                                return count;
                            });
            try {
                assertThat(observing.await(5, TimeUnit.SECONDS)).isTrue();
                var current = old;
                for (int i = 0; i < 20; i++) {
                    var next = new Session(old.sid(), UUID.randomUUID(), old.refreshExpiresAt());
                    assertThat(first.rotate(user, current, next)).isTrue();
                    current = next;
                }
            } finally {
                done.set(true);
            }
            assertThat(reads.get(5, TimeUnit.SECONDS)).isPositive();
        } finally {
            factory.destroy();
        }
    }
}
