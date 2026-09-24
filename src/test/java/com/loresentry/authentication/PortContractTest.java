package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.in.TokenPair;
import com.loresentry.authentication.application.port.out.SessionStore;
import com.loresentry.authentication.config.CoreConfiguration;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PortContractTest {
    @Test
    void sessionRejectsNonUuidV4AndFractionalExpiry() {
        var expiry = Instant.parse("2026-10-01T00:00:00Z");
        assertThatThrownBy(
                        () -> new SessionStore.Session(new UUID(0, 0), UUID.randomUUID(), expiry))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SessionStore.Session(
                                        UUID.randomUUID(), UUID.randomUUID(), expiry.plusNanos(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void consumptionHasThreeCallbackStatesAndNoCallbackStateForOtherFailures() {
        for (var state : AuthFailure.Consumption.values()) {
            assertThat(new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE, state).consumption())
                    .isEqualTo(state);
        }
        assertThat(new AuthFailure(AuthFailure.Reason.REFRESH_REJECTED).consumption()).isNull();
        assertThat(
                        new TokenPair(
                                        "access-secret",
                                        Instant.EPOCH,
                                        "refresh-secret",
                                        Instant.EPOCH)
                                .toString())
                .doesNotContain("access-secret", "refresh-secret");
    }

    @Test
    void contextProvidesUtcClockWithoutFakeProductionAdapters() {
        try (var context = new AnnotationConfigApplicationContext(CoreConfiguration.class)) {
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(context.getBeansOfType(SessionStore.class)).isEmpty();
        }
    }
}
