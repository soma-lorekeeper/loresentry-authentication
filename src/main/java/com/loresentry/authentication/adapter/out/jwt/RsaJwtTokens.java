package com.loresentry.authentication.adapter.out.jwt;

import lombok.RequiredArgsConstructor;
import com.loresentry.authentication.application.port.in.TokenPair;
import com.loresentry.authentication.application.port.out.JwtTokens;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

@RequiredArgsConstructor
public final class RsaJwtTokens implements JwtTokens {
    private final JwtKeys keys;
    private final Clock clock;

    public Issued issue(UUID userId) {
        Objects.requireNonNull(userId);
        var issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var accessExpiry = issuedAt.plus(Duration.ofMinutes(15));
        var refreshExpiry = issuedAt.plus(Duration.ofDays(14));
        var refreshId = UUID.randomUUID();
        try {
            var access = sign(userId, UUID.randomUUID(), issuedAt, accessExpiry, "access", "loresentry-api");
            var refresh = sign(userId, refreshId, issuedAt, refreshExpiry, "refresh", "loresentry-auth");
            return new Issued(new TokenPair(access, accessExpiry, refresh, refreshExpiry), refreshId);
        } catch (JOSEException failure) {
            throw new PortFailure(PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.NOT_EXECUTED, false);
        }
    }

    private String sign(UUID userId, UUID jti, Instant issuedAt, Instant expiry, String type, String audience) throws JOSEException {
        var claims = new JWTClaimsSet.Builder().subject(userId.toString()).issuer("loresentry-auth")
                .audience(audience).issueTime(Date.from(issuedAt)).expirationTime(Date.from(expiry))
                .jwtID(jti.toString()).claim("token_type", type).build();
        var token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keys.kid()).type(JOSEObjectType.JWT).build(), claims);
        token.sign(new RSASSASigner(keys.privateKey()));
        return token.serialize();
    }

    public RefreshClaims verifyRefresh(String raw, boolean allowExpired) {
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
            var jwt = SignedJWT.parse(raw);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm()) || !keys.kid().equals(jwt.getHeader().getKeyID())
                    || !jwt.verify(new RSASSAVerifier(keys.publicKey()))) throw new IllegalArgumentException();
            var claims = jwt.getJWTClaimsSet();
            if (!"loresentry-auth".equals(claims.getIssuer()) || !claims.getAudience().contains("loresentry-auth")
                    || !"refresh".equals(claims.getStringClaim("token_type"))) throw new IllegalArgumentException();
            var userId = uuid(claims.getSubject());
            var jti = uuid(claims.getJWTID());
            var issuedAt = Objects.requireNonNull(claims.getIssueTime()).toInstant();
            var expiry = Objects.requireNonNull(claims.getExpirationTime()).toInstant();
            var now = clock.instant();
            if (!expiry.isAfter(issuedAt) || issuedAt.isAfter(now.plusSeconds(30))
                    || (!allowExpired && !now.isBefore(expiry.plusSeconds(30)))) throw new IllegalArgumentException();
            return new RefreshClaims(userId, jti, expiry);
        } catch (Exception failure) {
            throw new PortFailure(PortFailure.Kind.INVALID_TOKEN, PortFailure.Execution.NOT_EXECUTED, false);
        }
    }

    private static UUID uuid(String value) {
        var result = UUID.fromString(value);
        if (!result.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return result;
    }
}
