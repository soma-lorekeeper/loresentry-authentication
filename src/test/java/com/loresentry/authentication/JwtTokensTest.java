package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.adapter.out.jwt.*;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.support.TestKeys;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import java.security.Signature;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class JwtTokensTest {
    private static final Instant NOW = Instant.parse("2026-09-17T00:00:00Z");
    private final JwtKeys keys =
            JwtKeys.load(TestKeys.PRIVATE, TestKeys.PUBLIC_FILE.toString(), TestKeys.KID);
    private final RsaJwtTokens tokens = new RsaJwtTokens(keys, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void issuedPairHasIndependentIdsCorrectLifetimesAndAnIndependentRsaSignature()
            throws Exception {
        var userId = UUID.randomUUID();
        var issued = tokens.issue(userId);
        var pair = issued.tokens();
        var at = SignedJWT.parse(pair.accessToken());
        var rt = SignedJWT.parse(pair.refreshToken());
        assertThat(pair.accessExpiresAt()).isEqualTo(NOW.plusSeconds(900));
        assertThat(pair.refreshExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(14)));
        assertThat(at.getJWTClaimsSet().getAudience()).containsExactly("loresentry-api");
        assertThat(at.getJWTClaimsSet().getStringClaim("token_type")).isEqualTo("access");
        assertThat(at.getJWTClaimsSet().getJWTID()).isNotEqualTo(rt.getJWTClaimsSet().getJWTID());
        assertThat(UUID.fromString(at.getJWTClaimsSet().getJWTID()).version()).isEqualTo(4);
        assertThat(tokens.verifyRefresh(pair.refreshToken(), false).userId()).isEqualTo(userId);
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(keys.publicKey());
        signature.update(at.getSigningInput());
        assertThat(signature.verify(at.getSignature().decode())).isTrue();
        assertThatThrownBy(() -> tokens.verifyRefresh(pair.accessToken(), false))
                .isInstanceOf(PortFailure.class);
    }

    @Test
    void rejectsMissingWrongAndMalformedRequiredClaims() throws Exception {
        for (var name : List.of("sub", "iss", "aud", "iat", "exp", "jti", "token_type")) {
            var claims = validClaims();
            claims.remove(name);
            reject(sign(claims, TestKeys.KID, JWSAlgorithm.RS256));
        }
        for (var entry :
                Map.<String, Object>of(
                                "sub",
                                "1-1-1-1-1",
                                "iss",
                                "other",
                                "aud",
                                List.of("loresentry-api"),
                                "jti",
                                "invalid",
                                "token_type",
                                "access")
                        .entrySet()) {
            var claims = validClaims();
            claims.put(entry.getKey(), entry.getValue());
            reject(sign(claims, TestKeys.KID, JWSAlgorithm.RS256));
        }
        reject(sign(validClaims(), null, JWSAlgorithm.RS256));
        reject(sign(validClaims(), "unknown", JWSAlgorithm.RS256));
        reject(sign(validClaims(), TestKeys.KID, JWSAlgorithm.RS384));
        reject("malformed");
        var claims = validClaims();
        claims.put("aud", List.of("other", "loresentry-auth"));
        assertThat(tokens.verifyRefresh(sign(claims, TestKeys.KID, JWSAlgorithm.RS256), false))
                .isNotNull();
    }

    @Test
    void validatesTimeBoundariesAndAllowsOnlyExpirationToBeIgnoredForRevocation() throws Exception {
        var claims = validClaims();
        claims.put("iat", NOW.plusSeconds(30).getEpochSecond());
        assertThat(tokens.verifyRefresh(sign(claims, TestKeys.KID, JWSAlgorithm.RS256), false))
                .isNotNull();
        claims.put("iat", NOW.plusSeconds(31).getEpochSecond());
        reject(sign(claims, TestKeys.KID, JWSAlgorithm.RS256));
        claims.put("iat", NOW.minusSeconds(1000).getEpochSecond());
        claims.put("exp", NOW.minusSeconds(29).getEpochSecond());
        assertThat(tokens.verifyRefresh(sign(claims, TestKeys.KID, JWSAlgorithm.RS256), false))
                .isNotNull();
        claims.put("exp", NOW.minusSeconds(30).getEpochSecond());
        var expired = sign(claims, TestKeys.KID, JWSAlgorithm.RS256);
        reject(expired);
        assertThat(tokens.verifyRefresh(expired, true)).isNotNull();
        claims.put("exp", claims.get("iat"));
        var reversed = sign(claims, TestKeys.KID, JWSAlgorithm.RS256);
        assertThatThrownBy(() -> tokens.verifyRefresh(reversed, true))
                .isInstanceOf(PortFailure.class);
    }

    @Test
    void replacedKeyRejectsPreviouslyIssuedTokensEvenWithReusedKid() throws Exception {
        var old = tokens.issue(UUID.randomUUID()).tokens().refreshToken();
        var pair = TestKeys.generate(2048);
        var replacement =
                JwtKeys.load(
                        Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()),
                        TestKeys.publicFile(pair).toString(),
                        UUID.randomUUID().toString());
        var after = new RsaJwtTokens(replacement, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> after.verifyRefresh(old, false)).isInstanceOf(PortFailure.class);
        var sameKid =
                new RsaJwtTokens(
                        new JwtKeys(
                                replacement.privateKey(), replacement.publicKey(), TestKeys.KID),
                        Clock.fixed(NOW, ZoneOffset.UTC));
        assertThatThrownBy(() -> sameKid.verifyRefresh(old, true)).isInstanceOf(PortFailure.class);
    }

    private Map<String, Object> validClaims() {
        return new HashMap<>(
                Map.of(
                        "sub",
                        UUID.randomUUID().toString(),
                        "iss",
                        "loresentry-auth",
                        "aud",
                        List.of("loresentry-auth"),
                        "iat",
                        NOW.getEpochSecond(),
                        "exp",
                        NOW.plusSeconds(60).getEpochSecond(),
                        "jti",
                        UUID.randomUUID().toString(),
                        "token_type",
                        "refresh"));
    }

    private String sign(Map<String, Object> claims, String kid, JWSAlgorithm algorithm)
            throws Exception {
        var jwt =
                new SignedJWT(
                        new JWSHeader.Builder(algorithm).keyID(kid).build(),
                        JWTClaimsSet.parse(claims));
        jwt.sign(new RSASSASigner(keys.privateKey()));
        return jwt.serialize();
    }

    private void reject(String token) {
        assertThatThrownBy(() -> tokens.verifyRefresh(token, false))
                .isInstanceOfSatisfying(
                        PortFailure.class,
                        failure ->
                                assertThat(failure.kind())
                                        .isEqualTo(PortFailure.Kind.INVALID_TOKEN));
    }
}
