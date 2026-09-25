package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.adapter.out.redis.RedisLoginSessionStore;
import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.LoginService;
import com.loresentry.authentication.domain.SessionId;
import com.loresentry.authentication.support.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpAuthTestSupport.GoogleHttpConfiguration.class)
class FullLoginFlowTest extends HttpAuthTestSupport {
    @Autowired ApplicationContext context;
    @Autowired StringRedisTemplate redis;

    @Test
    void loginIsWiredToTheNewSessionStore() {
        assertThat(context.getBean(LoginUseCase.class)).isInstanceOf(LoginService.class);
        assertThat(context.getBean(LoginSessionStore.class))
                .isInstanceOf(RedisLoginSessionStore.class);
    }

    @Test
    void loginReplacesTheCurrentHashAndKeepsTheAccountAndName() {
        var subject = "full-" + UUID.randomUUID();
        var first = login(subject);
        assertThat(first.body().size()).isEqualTo(3);
        assertThat(first.body().get("login_request_consumed").booleanValue()).isTrue();
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(first.headers().firstValue("Set-Cookie")).isEmpty();
        assertThat(first.body().has("access_token")).isFalse();
        assertThat(first.body().has("refresh_token")).isFalse();
        var id = new SessionId(first.body().get("session_id").asString());
        var record =
                mapper.readTree(redis.opsForValue().get("auth:session:{login}:by-id:" + id.hash()));
        var user = UUID.fromString(record.get("user_id").asString());
        assertThat(user.version()).isEqualTo(7);
        assertThat(Instant.parse(first.body().get("expires_at").asString()))
                .isBetween(Instant.now().plusSeconds(1209595), Instant.now().plusSeconds(1209601));
        var profile = call("GET", "/auth/users/me", null, user);
        assertThat(profile.status()).isEqualTo(200);
        assertThat(profile.body().get("id").asString()).isEqualTo(user.toString());
        var renamed = call("PATCH", "/auth/users/me", Map.of("display_name", "My Name"), user);
        assertThat(renamed.status()).isEqualTo(200);
        var second = login(subject);
        var next = new SessionId(second.body().get("session_id").asString());
        assertThat(next).isNotEqualTo(id);
        assertThat(redis.opsForValue().get("auth:session:{login}:by-user:" + user))
                .contains(next.hash())
                .doesNotContain(id.hash());
        assertThat(redis.opsForValue().get("auth:session:{login}:by-id:" + next.hash()))
                .contains(user.toString())
                .doesNotContain(next.value());
        assertThat(call("GET", "/auth/users/me", null, user).body().get("display_name").asString())
                .isEqualTo("My Name");
        assertThat(accounts.findByIdentity("google", subject).orElseThrow().user().id())
                .isEqualTo(user);
        assertThat(google.unexpectedCalls.get()).isZero();
    }

    @Test
    void httpRevocationAcceptsUnknownIdsAndNeverDeletesTheNewLogin() {
        var subject = "revoke-" + UUID.randomUUID();
        var first = login(subject);
        var old = new SessionId(first.body().get("session_id").asString());
        var next = new SessionId(login(subject).body().get("session_id").asString());
        var user = accounts.findByIdentity("google", subject).orElseThrow().user().id();
        for (int i = 0; i < 2; i++) {
            var result =
                    call("POST", "/auth/sessions/revoke", Map.of("session_id", old.value()), null);
            assertThat(result.status()).isEqualTo(204);
            assertThat(result.body()).isNull();
            assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
        }
        assertThat(redis.opsForValue().get("auth:session:{login}:by-user:" + user))
                .contains(next.hash());
        error(
                call("POST", "/auth/sessions/revoke", Map.of("session_id", "malformed"), null),
                400,
                "INVALID_SESSION_ID",
                "NONE");
        assertThat(
                        call(
                                        "POST",
                                        "/auth/sessions/revoke",
                                        Map.of("session_id", next.value()),
                                        null)
                                .status())
                .isEqualTo(204);
        assertThat(redis.hasKey("auth:session:{login}:by-user:" + user)).isFalse();
        assertThat(redis.hasKey("auth:session:{login}:by-id:" + next.hash())).isFalse();
    }
}
