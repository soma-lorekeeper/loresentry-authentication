package com.loresentry.authentication;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.TokenPair;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.application.port.out.RefreshTokenStore;
import com.loresentry.authentication.config.CoreConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.assertj.core.api.Assertions.*;

class PortContractTest {
    @Test
    void absenceIsDifferentFromUnknownConsumption() {
        var clock = Clock.fixed(Instant.parse("2026-09-17T00:00:00Z"), ZoneOffset.UTC);
        var store = new FakeRefreshStore(clock);
        var jti = UUID.randomUUID();
        var user = UUID.randomUUID();
        store.save(jti, user, clock.instant().plusSeconds(10));
        assertThat(store.consume(jti)).contains(user);
        assertThat(store.consume(jti)).isEmpty();
        store.failure = new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.UNKNOWN, true);
        assertThatThrownBy(() -> store.consume(jti)).isSameAs(store.failure);
        assertThat(store.failure.execution()).isEqualTo(PortFailure.Execution.UNKNOWN);
    }

    @Test
    void expiredFakeStateUsesInjectedClock() {
        var clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC);
        var store = new FakeRefreshStore(clock);
        var id = UUID.randomUUID();
        store.save(id, UUID.randomUUID(), Instant.EPOCH);
        assertThat(store.consume(id)).isEmpty();
    }

    @Test
    void consumptionHasThreeCallbackStatesAndNoCallbackStateForOtherFailures() {
        for (var state : AuthFailure.Consumption.values()) {
            assertThat(new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE, state).consumption()).isEqualTo(state);
        }
        assertThat(new AuthFailure(AuthFailure.Reason.REFRESH_REJECTED).consumption()).isNull();
        assertThat(new TokenPair("access-secret", Instant.EPOCH, "refresh-secret", Instant.EPOCH).toString())
                .doesNotContain("access-secret", "refresh-secret");
    }

    @Test
    void contextProvidesUtcClockWithoutFakeProductionAdapters() {
        try (var context = new AnnotationConfigApplicationContext(CoreConfiguration.class)) {
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(context.getBeansOfType(RefreshTokenStore.class)).isEmpty();
        }
    }

    private static final class FakeRefreshStore implements RefreshTokenStore {
        private record Entry(UUID user, Instant expiresAt) {}
        private final Clock clock;
        private final Map<UUID, Entry> entries = new HashMap<>();
        private PortFailure failure;
        private FakeRefreshStore(Clock clock) { this.clock = clock; }
        public void save(UUID jti, UUID userId, Instant expiresAt) { entries.put(jti, new Entry(userId, expiresAt)); }
        public Optional<UUID> consume(UUID jti) {
            if (failure != null) throw failure;
            var entry = entries.remove(jti);
            return entry != null && clock.instant().isBefore(entry.expiresAt())
                    ? Optional.of(entry.user()) : Optional.empty();
        }
        public void delete(UUID jti) { entries.remove(jti); }
    }
}
