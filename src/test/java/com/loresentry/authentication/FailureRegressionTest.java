package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.adapter.out.persistence.JpaAccountStore;
import com.loresentry.authentication.adapter.out.redis.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.OAuthSecrets;
import com.loresentry.authentication.support.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpAuthTestSupport.GoogleHttpConfiguration.class)
class FailureRegressionTest extends HttpAuthTestSupport {
    @MockitoSpyBean RedisOAuthStateStore stateAdapter;
    @MockitoSpyBean SessionIdGenerator idGenerator;
    @MockitoSpyBean RedisLoginSessionStore sessionAdapter;
    @MockitoSpyBean JpaAccountStore accountAdapter;
    @Autowired StringRedisTemplate redis;

    @Test
    void concurrentSignupCreatesOneAccountAndRollsBackTheLoser() throws Exception {
        var subject = "signup-" + UUID.randomUUID();
        var first = pending(subject);
        var second = pending(subject);
        var bothRead = new CountDownLatch(2);
        doAnswer(
                        call -> {
                            var found = call.callRealMethod();
                            if (((Optional<?>) found).isEmpty()) {
                                bothRead.countDown();
                                assertThat(bothRead.await(5, TimeUnit.SECONDS)).isTrue();
                            }
                            return found;
                        })
                .when(accountAdapter)
                .findByIdentity("google", subject);
        var results = concurrent(List.of(() -> callback(first), () -> callback(second)));
        assertThat(results).allSatisfy(r -> assertThat(r.status()).isEqualTo(200));
        var user = userOf(results.getFirst());
        assertThat(userOf(results.getLast())).isEqualTo(user);
        assertThat(accounts.findByIdentity("google", subject).orElseThrow().user().id())
                .isEqualTo(user);
    }

    @Test
    void simultaneousCallbacksHaveExactlyOneWinner() throws Exception {
        var pending = pending("callback-" + UUID.randomUUID());
        int callsBefore = google.tokenCalls.get();
        var callbacks = concurrent(Collections.nCopies(5, () -> callback(pending)));
        assertThat(callbacks.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        for (var result : callbacks)
            if (result.status() != 200) {
                error(result, 400, "OAUTH_REQUEST_INVALID", "RESTART_LOGIN");
                assertThat(result.body().get("login_request_consumed").booleanValue()).isFalse();
            }
        assertThat(google.tokenCalls.get() - callsBefore).isEqualTo(1);
    }

    @Test
    void callbackConsumptionResponseLossIsUnknownAndStateStaysDeleted() {
        var pending = pending("unknown-" + UUID.randomUUID());
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            throw unavailable(PortFailure.Execution.UNKNOWN);
                        })
                .when(stateAdapter)
                .consume(pending.id());
        var result = callback(pending);
        error(result, 503, "LOGIN_UNAVAILABLE", "RESTART_LOGIN");
        assertThat(result.body().get("login_request_consumed").isNull()).isTrue();
        assertThat(states.find(pending.id())).isEmpty();
        verify(stateAdapter, times(1)).consume(pending.id());
    }

    @Test
    void commitSurvivesFailedLoginSaveAndReloginKeepsTheUuid() {
        var subject = "commit-http-" + UUID.randomUUID();
        var pending = pending(subject);
        doThrow(unavailable(PortFailure.Execution.UNKNOWN))
                .when(sessionAdapter)
                .replace(any(), any());
        var failed = callback(pending);
        error(failed, 503, "LOGIN_UNAVAILABLE", "RESTART_LOGIN");
        assertThat(failed.body().get("login_request_consumed").booleanValue()).isTrue();
        var committed = accounts.findByIdentity("google", subject).orElseThrow().user().id();
        reset(sessionAdapter);
        assertThat(userOf(login(subject))).isEqualTo(committed);
    }

    @Test
    void lostLoginResponseIsNotReplayedAndCommittedAccountSurvives() {
        var subject = "lost-response-" + UUID.randomUUID();
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            throw unavailable(PortFailure.Execution.UNKNOWN);
                        })
                .when(sessionAdapter)
                .replace(any(), any());
        var response = callback(pending(subject));
        error(response, 503, "LOGIN_UNAVAILABLE", "RESTART_LOGIN");
        assertThat(response.body().has("session_id")).isFalse();
        var user = accounts.findByIdentity("google", subject).orElseThrow().user().id();
        assertThat(redis.opsForValue().get("auth:session:{login}:by-user:" + user)).isNotNull();
        verify(sessionAdapter, times(1)).replace(eq(user), any());
    }

    @Test
    @org.junit.jupiter.api.extension.ExtendWith(
            org.springframework.boot.test.system.OutputCaptureExtension.class)
    void generationAndCorruptStateFailuresNeverExposeSecrets(
            org.springframework.boot.test.system.CapturedOutput output) {
        var subject = "internal-" + UUID.randomUUID();
        var initial = login(subject);
        var user = userOf(initial);
        var key = "auth:session:{login}:by-user:" + user;
        var before = redis.opsForValue().get(key);
        doThrow(new IllegalStateException("private-generator-detail")).when(idGenerator).generate();
        error(callback(pending(subject)), 500, "INTERNAL_ERROR", "NONE");
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
        reset(idGenerator);
        redis.opsForValue().set(key, "corrupt-session-secret");
        error(callback(pending(subject)), 503, "LOGIN_UNAVAILABLE", "RESTART_LOGIN");
        assertThat(redis.opsForValue().get(key)).isEqualTo("corrupt-session-secret");
        assertThat(output.getAll())
                .doesNotContain(
                        "private-generator-detail",
                        "corrupt-session-secret",
                        initial.body().get("session_id").asString());
    }

    @Test
    void loginReplacementDistinguishesPortFailureFromUnclassifiedDefect() {
        for (var execution :
                List.of(PortFailure.Execution.NOT_EXECUTED, PortFailure.Execution.UNKNOWN)) {
            var subject = "login-port-" + UUID.randomUUID();
            doThrow(unavailable(execution)).when(sessionAdapter).replace(any(), any());
            var result = callback(pending(subject));
            error(result, 503, "LOGIN_UNAVAILABLE", "RESTART_LOGIN");
            assertThat(result.body().get("login_request_consumed").booleanValue()).isTrue();
            assertThat(result.headers().firstValue("Cache-Control")).contains("no-store");
            reset(sessionAdapter);
        }
        doThrow(new IllegalStateException("private-token-detail"))
                .when(sessionAdapter)
                .replace(any(), any());
        var result = callback(pending("login-defect-" + UUID.randomUUID()));
        error(result, 500, "INTERNAL_ERROR", "NONE");
        assertThat(result.body().get("login_request_consumed").booleanValue()).isTrue();
        assertThat(result.body().toString()).doesNotContain("private-token-detail");
    }

    @Test
    void wrongStatePreservesRequestAndNonceOrPkceFailureNeverCreatesAnAccount() {
        var subject = "identity-fail-" + UUID.randomUUID();
        var pending = pending(subject);
        var original = states.find(pending.id()).orElseThrow();
        var mismatch =
                call(
                        "POST",
                        "/auth/oauth/google/callback",
                        Map.of(
                                "login_request_id",
                                pending.id(),
                                "state",
                                OAuthSecrets.generate(new java.security.SecureRandom()),
                                "code",
                                pending.code()),
                        null);
        error(mismatch, 400, "OAUTH_REQUEST_INVALID", "RESTART_LOGIN");
        assertThat(states.find(pending.id())).contains(original);
        google.tokens.put(
                pending.code(), google.sign(google.claims("wrong-nonce", subject).build()));
        var invalidNonce = callback(pending);
        error(invalidNonce, 401, "OAUTH_IDENTITY_INVALID", "RESTART_LOGIN");
        assertThat(invalidNonce.body().get("login_request_consumed").booleanValue()).isTrue();
        assertThat(accounts.findByIdentity("google", subject)).isEmpty();
        var next = pending(subject);
        var value = states.find(next.id()).orElseThrow();
        var tampered =
                new OAuthStateStore.State(
                        1,
                        value.registrationId(),
                        value.clientId(),
                        value.redirectUri(),
                        value.state(),
                        value.nonce(),
                        OAuthSecrets.generate(new java.security.SecureRandom()),
                        value.createdAt(),
                        value.expiresAt());
        redis.opsForValue()
                .set("auth:oauth:" + next.id(), new OAuthStateJson(mapper).encode(tampered));
        var invalidPkce = callback(next);
        error(invalidPkce, 401, "OAUTH_IDENTITY_INVALID", "RESTART_LOGIN");
        assertThat(invalidPkce.body().get("login_request_consumed").booleanValue()).isTrue();
        assertThat(accounts.findByIdentity("google", subject)).isEmpty();
        assertThat(states.find(next.id())).isEmpty();
    }

    private UUID userOf(Result response) {
        var id =
                new com.loresentry.authentication.domain.SessionId(
                        response.body().get("session_id").asString());
        return UUID.fromString(
                mapper.readTree(redis.opsForValue().get("auth:session:{login}:by-id:" + id.hash()))
                        .get("user_id")
                        .asString());
    }

    private PortFailure unavailable(PortFailure.Execution execution) {
        return new PortFailure(PortFailure.Kind.UNAVAILABLE, execution, true);
    }

    private List<Result> concurrent(List<Supplier<Result>> tasks) throws Exception {
        var gate = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Result>> futures = new ArrayList<>();
            for (var task : tasks)
                futures.add(
                        executor.submit(
                                () -> {
                                    gate.await();
                                    return task.get();
                                }));
            gate.countDown();
            List<Result> results = new ArrayList<>();
            for (var future : futures) results.add(future.get(15, TimeUnit.SECONDS));
            return results;
        }
    }
}
