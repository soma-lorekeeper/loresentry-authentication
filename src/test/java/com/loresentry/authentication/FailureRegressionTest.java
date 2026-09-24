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
import java.util.concurrent.atomic.AtomicReference;
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
    @MockitoSpyBean RedisRefreshTokenStore tokenAdapter;
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
        var user = jwt.verifyRefresh(rt(results.getFirst()), false).userId();
        assertThat(jwt.verifyRefresh(rt(results.getLast()), false).userId()).isEqualTo(user);
        assertThat(accounts.findByIdentity("google", subject).orElseThrow().user().id())
                .isEqualTo(user);
    }

    @Test
    void simultaneousCallbacksAndRefreshesEachHaveExactlyOneWinner() throws Exception {
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
        var token = rt(callbacks.stream().filter(r -> r.status() == 200).findFirst().orElseThrow());
        var refreshes = concurrent(Collections.nCopies(5, () -> refresh(token)));
        assertThat(refreshes.stream().filter(r -> r.status() == 200).count()).isEqualTo(1);
        for (var result : refreshes)
            if (result.status() != 200) error(result, 401, "REFRESH_REJECTED", "RELOGIN");
        // The winning response is deliberately discarded; retrying the previous RT cannot recover
        // it.
        error(refresh(token), 401, "REFRESH_REJECTED", "RELOGIN");
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
                .when(tokenAdapter)
                .save(any(), any(), any());
        var failed = callback(pending);
        error(failed, 503, "LOGIN_UNAVAILABLE", "RESTART_LOGIN");
        assertThat(failed.body().get("login_request_consumed").booleanValue()).isTrue();
        var committed = accounts.findByIdentity("google", subject).orElseThrow().user().id();
        reset(tokenAdapter);
        assertThat(jwt.verifyRefresh(rt(login(subject)), false).userId()).isEqualTo(committed);
    }

    @Test
    void refreshFailuresDistinguishNotExecutedUnknownAndFailedNewSave() {
        var initial = login("refresh-fail-" + UUID.randomUUID());
        var token = rt(initial);
        var old = jwt.verifyRefresh(token, false).jti();
        doThrow(unavailable(PortFailure.Execution.NOT_EXECUTED)).when(tokenAdapter).consume(old);
        error(refresh(token), 503, "REFRESH_UNAVAILABLE", "RETRY_LATER");
        assertThat(redis.hasKey("auth:refresh:" + old)).isTrue();
        doAnswer(
                        call -> {
                            call.callRealMethod();
                            throw unavailable(PortFailure.Execution.UNKNOWN);
                        })
                .when(tokenAdapter)
                .consume(old);
        error(refresh(token), 503, "REFRESH_OUTCOME_UNKNOWN", "RELOGIN");
        assertThat(redis.hasKey("auth:refresh:" + old)).isFalse();
        reset(tokenAdapter);
        var another = login("save-fail-" + UUID.randomUUID());
        var previous = jwt.verifyRefresh(rt(another), false).jti();
        AtomicReference<UUID> newJti = new AtomicReference<>();
        doAnswer(
                        call -> {
                            newJti.set(call.getArgument(0));
                            call.callRealMethod();
                            throw unavailable(PortFailure.Execution.UNKNOWN);
                        })
                .when(tokenAdapter)
                .save(any(), any(), any());
        var failed = refresh(rt(another));
        error(failed, 503, "REFRESH_SAVE_FAILED", "RELOGIN");
        assertThat(redis.hasKey("auth:refresh:" + previous)).isFalse();
        assertThat(redis.hasKey("auth:refresh:" + newJti.get())).isTrue();
        verify(tokenAdapter, times(1)).save(eq(newJti.get()), any(), any());
        error(refresh(rt(another)), 401, "REFRESH_REJECTED", "RELOGIN");
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

    @Test
    void failedRevocationReportsUnconfirmedAndDoesNotDeleteAnotherDevice() {
        var subject = "revoke-fail-" + UUID.randomUUID();
        var first = login(subject);
        var second = login(subject);
        var token = rt(first);
        var claims = jwt.verifyRefresh(token, false);
        var other = jwt.verifyRefresh(rt(second), false).jti();
        doThrow(unavailable(PortFailure.Execution.UNKNOWN)).when(tokenAdapter).delete(claims.jti());
        error(
                call("POST", "/auth/tokens/revoke", Map.of("refresh_token", token), null),
                503,
                "REVOCATION_UNCONFIRMED",
                "NONE");
        verify(tokenAdapter, times(3)).delete(claims.jti());
        assertThat(redis.hasKey("auth:refresh:" + claims.jti())).isTrue();
        assertThat(redis.hasKey("auth:refresh:" + other)).isTrue();
        verify(tokenAdapter, never()).delete(other);
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
