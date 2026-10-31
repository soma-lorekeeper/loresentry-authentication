package com.loresentry.authentication.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.adapter.in.web.RegistrationAnalyticsController;
import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.out.ConsentRequestStore;
import com.loresentry.authentication.domain.ConsentId;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RegistrationAnalyticsControllerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final ConsentRequestStore requests = mock(ConsentRequestStore.class);
    private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final RegistrationAnalyticsController controller =
            new RegistrationAnalyticsController(jdbc, requests, Clock.fixed(now, ZoneOffset.UTC));

    @Test
    void claimsARegistrationOnlyOnce() {
        var user = UUID.randomUUID();
        when(jdbc.update(anyString(), eq(user))).thenReturn(1, 0);
        assertThat(controller.user(user.toString()).getBody().registered()).isTrue();
        assertThat(controller.user(user.toString()).getBody().registered()).isFalse();
    }

    @Test
    void consentMustBeValidAndUnexpired() {
        var user = UUID.randomUUID();
        var token = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        var consent = new ConsentId(token);
        when(requests.find(consent))
                .thenReturn(
                        Optional.of(
                                new ConsentRequestStore.Pending(
                                        user,
                                        UUID.randomUUID(),
                                        now.minusSeconds(1),
                                        now.plusSeconds(60))));
        when(jdbc.update(anyString(), eq(user))).thenReturn(1);
        assertThat(controller.consent(token).getBody().registered()).isTrue();
        when(requests.find(consent)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.consent(token)).isInstanceOf(AuthFailure.class);
    }
}
