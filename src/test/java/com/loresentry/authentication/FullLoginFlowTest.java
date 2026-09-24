package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.adapter.out.google.GoogleOidcClient;
import com.loresentry.authentication.adapter.out.jwt.RsaJwtTokens;
import com.loresentry.authentication.adapter.out.persistence.JpaAccountStore;
import com.loresentry.authentication.adapter.out.redis.*;
import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.*;
import com.loresentry.authentication.support.*;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.util.ReflectionTestUtils;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(HttpAuthTestSupport.GoogleHttpConfiguration.class)
class FullLoginFlowTest extends HttpAuthTestSupport {
    @Autowired ApplicationContext context;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;

    @Test
    void productionCoreAndAdaptersAreWiredThroughConfiguration() {
        assertThat(context.getBean(LoginUseCase.class)).isInstanceOf(LoginService.class);
        assertThat(context.getBean(RefreshUseCase.class)).isInstanceOf(RefreshService.class);
        assertThat(context.getBean(RevokeUseCase.class)).isInstanceOf(RevokeService.class);
        assertThat(context.getBean(AccountUseCase.class)).isInstanceOf(AccountService.class);
        assertThat(context.getBean(RegisterIdentityUseCase.class))
                .isInstanceOf(RegistrationService.class);
        assertThat(accounts).isInstanceOf(JpaAccountStore.class);
        assertThat(states).isInstanceOf(RedisOAuthStateStore.class);
        assertThat(sessions).isInstanceOf(RedisSessionStore.class);
        assertThat(jwt).isInstanceOf(RsaJwtTokens.class);
        var provider = context.getBean(OidcClient.class);
        assertThat(provider).isInstanceOf(GoogleOidcClient.class);
        assertThat(ReflectionTestUtils.getField(context.getBean(LoginUseCase.class), "oidcClient"))
                .isSameAs(provider);
        assertThat(ReflectionTestUtils.getField(context.getBean(RefreshUseCase.class), "store"))
                .isSameAs(sessions);
    }

    @Test
    void fullHttpLifecycleReplacesSessionAndKeepsIdentity() throws Exception {
        var subject = "full-" + UUID.randomUUID();
        var first = login(subject);
        assertThat(first.body().get("login_request_consumed").booleanValue()).isTrue();
        assertThat(first.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(first.headers().firstValue("Set-Cookie")).isEmpty();
        var access = SignedJWT.parse(first.body().get("access_token").asString());
        assertThat(access.verify(new RSASSAVerifier((RSAPublicKey) TestKeys.PAIR.getPublic())))
                .isTrue();
        assertThat(access.getJWTClaimsSet().getIssuer()).isEqualTo("loresentry-auth");
        assertThat(access.getJWTClaimsSet().getAudience()).containsExactly("loresentry-api");
        UUID user = UUID.fromString(access.getJWTClaimsSet().getSubject());
        assertThat(user.version()).isEqualTo(7);
        var profile = call("GET", "/auth/users/me", null, user);
        assertThat(profile.status()).isEqualTo(200);
        assertThat(profile.body().get("id").asString()).isEqualTo(user.toString());
        var renamed = call("PATCH", "/auth/users/me", Map.of("display_name", "My Name"), user);
        assertThat(renamed.status()).isEqualTo(200);
        assertThat(renamed.body().get("display_name").asString()).isEqualTo("My Name");
        var second = login(subject);
        assertThat(jwt.verifyRefresh(rt(second), false).userId()).isEqualTo(user);
        assertThat(call("GET", "/auth/users/me", null, user).body().get("display_name").asString())
                .isEqualTo("My Name");
        var firstClaims = jwt.verifyRefresh(rt(first), false);
        var secondClaims = jwt.verifyRefresh(rt(second), false);
        assertThat(secondClaims.sid()).isNotEqualTo(firstClaims.sid());
        var active = mapper.readTree(redis.opsForValue().get("auth:session:" + user));
        assertThat(active.get("sid").asString()).isEqualTo(secondClaims.sid().toString());
        assertThat(access.getJWTClaimsSet().getStringClaim("sid"))
                .isNotEqualTo(active.get("sid").asString());
        // This is the shared state contract, not a BFF protected-route test.
        error(refresh(rt(first)), 401, "REFRESH_REJECTED", "RELOGIN");
        assertThat(
                        call(
                                        "POST",
                                        "/auth/tokens/revoke",
                                        Map.of("refresh_token", rt(first)),
                                        null)
                                .status())
                .isEqualTo(204);
        var rotated = refresh(rt(second));
        assertThat(rotated.status()).isEqualTo(200);
        assertThat(jwt.verifyRefresh(rt(rotated), false).sid()).isEqualTo(secondClaims.sid());
        assertThat(rotated.body().has("login_request_consumed")).isFalse();
        error(refresh(rt(second)), 401, "REFRESH_REJECTED", "RELOGIN");
        var revoked =
                call("POST", "/auth/tokens/revoke", Map.of("refresh_token", rt(rotated)), null);
        assertThat(revoked.status()).isEqualTo(204);
        assertThat(revoked.body()).isNull();
        error(refresh(rt(rotated)), 401, "REFRESH_REJECTED", "RELOGIN");
        assertThat(accounts.findByIdentity("google", subject).orElseThrow().user().id())
                .isEqualTo(user);
        assertThat(google.unexpectedCalls.get()).isZero();
    }

    @Test
    void legacyTokensAndLegacyKeysCannotRestoreADeletedSession() throws Exception {
        var initial = login("legacy-" + UUID.randomUUID());
        var claims = jwt.verifyRefresh(rt(initial), false);
        var parsed = SignedJWT.parse(rt(initial));
        var legacy =
                new SignedJWT(
                        parsed.getHeader(),
                        new com.nimbusds.jwt.JWTClaimsSet.Builder(parsed.getJWTClaimsSet())
                                .claim("sid", null)
                                .build());
        legacy.sign(new com.nimbusds.jose.crypto.RSASSASigner(TestKeys.PAIR.getPrivate()));
        var key = "auth:session:" + claims.userId();
        var before = redis.opsForValue().get(key);
        redis.opsForValue()
                .set(
                        "auth:refresh:" + claims.jti(),
                        claims.userId().toString(),
                        java.time.Duration.ofMinutes(5));
        error(refresh(legacy.serialize()), 401, "REFRESH_REJECTED", "RELOGIN");
        error(
                call(
                        "POST",
                        "/auth/tokens/revoke",
                        Map.of("refresh_token", legacy.serialize()),
                        null),
                401,
                "INVALID_REFRESH_TOKEN",
                "NONE");
        assertThat(redis.opsForValue().get(key)).isEqualTo(before);
        redis.expireAt(key, java.time.Instant.EPOCH);
        error(refresh(rt(initial)), 401, "REFRESH_REJECTED", "RELOGIN");
        assertThat(redis.hasKey(key)).isFalse();
        assertThat(redis.hasKey("auth:refresh:" + claims.jti())).isTrue();
    }

    @Test
    void anotherUsersSessionSurvivesRefreshAndLogout() {
        var one = login("user-one-" + UUID.randomUUID());
        var two = login("user-two-" + UUID.randomUUID());
        var userTwo = jwt.verifyRefresh(rt(two), false).userId();
        var before = redis.opsForValue().get("auth:session:" + userTwo);
        var rotated = refresh(rt(one));
        assertThat(rotated.status()).isEqualTo(200);
        assertThat(
                        call(
                                        "POST",
                                        "/auth/tokens/revoke",
                                        Map.of("refresh_token", rt(rotated)),
                                        null)
                                .status())
                .isEqualTo(204);
        assertThat(redis.opsForValue().get("auth:session:" + userTwo)).isEqualTo(before);
        assertThat(refresh(rt(two)).status()).isEqualTo(200);
    }
}
