package com.loresentry.authentication.adapter.out.redis;

import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.OAuthRequests;
import com.loresentry.authentication.domain.OAuthSecrets;
import com.loresentry.authentication.support.DatabaseTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
class OAuthStateStoreTest extends DatabaseTestSupport {
    @Autowired RedisOAuthStateStore store;
    @Autowired StringRedisTemplate redis;
    @Autowired JsonMapper mapper;
    private final SecureRandom random = new SecureRandom();
    private OAuthStateStore.State state() {
        var now = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new OAuthStateStore.State(1, "google", "client", "https://api.loresentry.com/auth/oauth/google/callback",
            OAuthSecrets.generate(random), OAuthSecrets.generate(random), OAuthSecrets.generate(random), now, now.plusSeconds(300));
    }
    @Test void jsonRoundTripRejectsMissingUnknownAndMalformedFields() {
        var codec = new OAuthStateJson(mapper); var value = state(); var encoded = codec.encode(value);
        assertThat(codec.decode(encoded)).isEqualTo(value);
        assertThat(encoded).doesNotContain("@class", "java.");
        for (String invalid : List.of("{", "[]", encoded.replace("\"schema_version\":1", "\"schema_version\":2"),
                encoded.replace("\"schema_version\":1", "\"schema_version\":1.2"),
                encoded.replace("\"state\":", "\"other\":"), encoded.replace("\"client\"", "null")))
            assertThatThrownBy(() -> codec.decode(invalid)).isInstanceOf(PortFailure.class);
    }
    @Test void ttlAndNxPreserveExistingState() {
        var id = OAuthSecrets.generate(random); var value = state();
        assertThat(store.create(id, value)).isTrue();
        assertThat(redis.getExpire("auth:oauth:" + id)).isBetween(295L, 300L);
        assertThat(store.create(id, state())).isFalse();
        assertThat(store.find(id)).contains(value);
        redis.delete("auth:oauth:" + id);
    }
    @Test void independentEntropyAndStandardPkceVector() {
        Set<String> values = new HashSet<>();
        for (int i = 0; i < 100; i++) {
            var value = OAuthSecrets.generate(random);
            assertThat(value).matches("[A-Za-z0-9_-]{43}");
            assertThat(Base64.getUrlDecoder().decode(value)).hasSize(32);
            assertThat(values.add(value)).isTrue();
        }
        assertThat(OAuthSecrets.hash("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
            .isEqualTo("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM");
    }
    @Test void prepareUsesNewIdentifierOnCollision() {
        var target = mock(OAuthStateStore.class); var provider = mock(OidcClient.class);
        when(provider.settings()).thenReturn(new OidcClient.Settings("google", "client", "https://callback"));
        when(provider.authorizationUrl(any())).thenReturn(URI.create("https://accounts.google.com/authorize"));
        when(target.create(anyString(), any())).thenReturn(false, true);
        var service = new OAuthRequests(target, provider, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), random);
        var result = service.prepare();
        var ids = org.mockito.ArgumentCaptor.forClass(String.class);
        var states = org.mockito.ArgumentCaptor.forClass(OAuthStateStore.State.class);
        verify(target, times(2)).create(ids.capture(), states.capture());
        assertThat(ids.getAllValues().get(0)).isNotEqualTo(ids.getAllValues().get(1));
        var state = states.getValue();
        assertThat(Set.of(result.loginRequestId(), state.state(), state.nonce(), state.codeVerifier())).hasSize(4);
        assertThat(result.expiresAt()).isEqualTo(Instant.EPOCH.plusSeconds(300));
    }
}
