package com.loresentry.authentication.adapter.out.jwt;

import com.loresentry.authentication.application.port.in.TokenPair;
import com.loresentry.authentication.application.port.out.JwtTokens;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jwt.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import lombok.RequiredArgsConstructor;

/**
 * RSA 키로 RS256 서명 JWT를 발급하고 RT의 서명과 클레임을 검증한다.
 *
 * <p>AT의 수명은 15분, RT는 14일이다. RT 검증 시 발급·만료 시각에 30초의 시계 오차를 허용한다. 저장소의 RT 소모·폐기 여부는 이 클래스가 검사하지 않는다.
 */
@RequiredArgsConstructor
public final class RsaJwtTokens implements JwtTokens {
    private final JwtKeys keys;
    private final Clock clock;

    public Issued issue(UUID userId, UUID sid) {
        Objects.requireNonNull(userId);
        Objects.requireNonNull(sid);
        if (sid.version() != 4 || sid.variant() != 2)
            throw new IllegalArgumentException("Invalid session id");
        var issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var accessExpiry = issuedAt.plus(Duration.ofMinutes(15));
        var refreshExpiry = issuedAt.plus(Duration.ofDays(14));
        var refreshId = UUID.randomUUID();
        try {
            var access =
                    sign(
                            userId,
                            sid,
                            UUID.randomUUID(),
                            issuedAt,
                            accessExpiry,
                            "access",
                            "loresentry-api");
            var refresh =
                    sign(
                            userId,
                            sid,
                            refreshId,
                            issuedAt,
                            refreshExpiry,
                            "refresh",
                            "loresentry-auth");
            return new Issued(
                    new TokenPair(access, accessExpiry, refresh, refreshExpiry), refreshId);
        } catch (JOSEException failure) {
            throw new PortFailure(
                    PortFailure.Kind.UNAVAILABLE, PortFailure.Execution.NOT_EXECUTED, false);
        }
    }

    private String sign(
            UUID userId,
            UUID sid,
            UUID jti,
            Instant issuedAt,
            Instant expiry,
            String type,
            String audience)
            throws JOSEException {
        var claims =
                new JWTClaimsSet.Builder()
                        .subject(userId.toString())
                        .issuer("loresentry-auth")
                        .audience(audience)
                        .issueTime(Date.from(issuedAt))
                        .expirationTime(Date.from(expiry))
                        .jwtID(jti.toString())
                        .claim("sid", sid.toString())
                        .claim("token_type", type)
                        .build();
        var token =
                new SignedJWT(
                        new JWSHeader.Builder(JWSAlgorithm.RS256)
                                .keyID(keys.kid())
                                .type(JOSEObjectType.JWT)
                                .build(),
                        claims);
        token.sign(new RSASSASigner(keys.privateKey()));
        return token.serialize();
    }

    public RefreshClaims verifyRefresh(String raw, boolean allowExpired) {
        try {
            if (raw == null || raw.isBlank()) throw new IllegalArgumentException();
            var jwt = SignedJWT.parse(raw);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())
                    || !keys.kid().equals(jwt.getHeader().getKeyID())
                    || !jwt.verify(new RSASSAVerifier(keys.publicKey())))
                throw new IllegalArgumentException();
            var claims = jwt.getJWTClaimsSet();
            if (!"loresentry-auth".equals(claims.getIssuer())
                    || !claims.getAudience().contains("loresentry-auth")
                    || !"refresh".equals(claims.getStringClaim("token_type")))
                throw new IllegalArgumentException();
            var userId = uuid(claims.getSubject());
            var jti = uuid(claims.getJWTID());
            var sid = uuid(claims.getStringClaim("sid"));
            if (sid.version() != 4 || sid.variant() != 2) throw new IllegalArgumentException();
            var issuedAt = Objects.requireNonNull(claims.getIssueTime()).toInstant();
            var expiry = Objects.requireNonNull(claims.getExpirationTime()).toInstant();
            var now = clock.instant();
            if (!expiry.isAfter(issuedAt)
                    || issuedAt.isAfter(now.plusSeconds(30))
                    || (!allowExpired && !now.isBefore(expiry.plusSeconds(30))))
                throw new IllegalArgumentException();
            return new RefreshClaims(userId, sid, jti, expiry);
        } catch (Exception failure) {
            throw new PortFailure(
                    PortFailure.Kind.INVALID_TOKEN, PortFailure.Execution.NOT_EXECUTED, false);
        }
    }

    private static UUID uuid(String value) {
        var result = UUID.fromString(value);
        if (!result.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException();
        return result;
    }
}
