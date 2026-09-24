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
        assertThat(refreshStates).isInstanceOf(RedisRefreshTokenStore.class);
        assertThat(jwt).isInstanceOf(RsaJwtTokens.class);
        var provider = context.getBean(OidcClient.class);
        assertThat(provider).isInstanceOf(GoogleOidcClient.class);
        assertThat(ReflectionTestUtils.getField(context.getBean(LoginUseCase.class), "oidcClient"))
                .isSameAs(provider);
        assertThat(ReflectionTestUtils.getField(context.getBean(RefreshUseCase.class), "store"))
                .isSameAs(refreshStates);
    }

    @Test
    void fullHttpLifecycleKeepsIdentityAndOtherDeviceState() throws Exception {
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
        var oldJti = jwt.verifyRefresh(rt(first), false).jti();
        var deviceJti = jwt.verifyRefresh(rt(second), false).jti();
        var rotated = refresh(rt(first));
        assertThat(rotated.status()).isEqualTo(200);
        assertThat(refreshStates.consume(oldJti)).isEmpty();
        assertThat(rotated.body().has("login_request_consumed")).isFalse();
        var revoked =
                call("POST", "/auth/tokens/revoke", Map.of("refresh_token", rt(rotated)), null);
        assertThat(revoked.status()).isEqualTo(204);
        assertThat(revoked.body()).isNull();
        error(refresh(rt(rotated)), 401, "REFRESH_REJECTED", "RELOGIN");
        assertThat(refresh(rt(second)).status()).isEqualTo(200);
        assertThat(refreshStates.consume(deviceJti)).isEmpty();
        assertThat(accounts.findByIdentity("google", subject).orElseThrow().user().id())
                .isEqualTo(user);
        assertThat(google.unexpectedCalls.get()).isZero();
    }
}
