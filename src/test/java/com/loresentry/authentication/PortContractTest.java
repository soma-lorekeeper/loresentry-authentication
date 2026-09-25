package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.out.LoginSessionStore;
import com.loresentry.authentication.config.CoreConfiguration;
import java.time.Clock;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class PortContractTest {
    @Test
    void consumptionHasThreeCallbackStatesAndNoCallbackStateForOtherFailures() {
        for (var state : AuthFailure.Consumption.values()) {
            assertThat(new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE, state).consumption())
                    .isEqualTo(state);
        }
        assertThat(new AuthFailure(AuthFailure.Reason.INVALID_SESSION_ID).consumption()).isNull();
    }

    @Test
    void contextProvidesUtcClockWithoutFakeProductionAdapters() {
        try (var context = new AnnotationConfigApplicationContext(CoreConfiguration.class)) {
            assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(context.getBeansOfType(LoginSessionStore.class)).isEmpty();
        }
    }
}
