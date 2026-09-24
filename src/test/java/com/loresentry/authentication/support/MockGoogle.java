package com.loresentry.authentication.support;

import com.loresentry.authentication.adapter.out.google.GoogleOidcClient;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jwt.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.json.JsonMapper;

public final class MockGoogle implements AutoCloseable {
    public final java.security.KeyPair keys = TestKeys.generate(2048);
    public final String kid = UUID.randomUUID().toString();
    public final Map<String, String> tokens = new ConcurrentHashMap<>();
    public final Map<String, String> expectedVerifiers = new ConcurrentHashMap<>();
    public final AtomicInteger tokenCalls = new AtomicInteger(),
            keyCalls = new AtomicInteger(),
            unexpectedCalls = new AtomicInteger();
    public volatile Map<String, String> lastForm = Map.of();
    public volatile String lastAuthorization;
    public volatile long tokenDelayMillis;
    private final HttpServer server;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public MockGoogle() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext(
                    "/token",
                    exchange -> {
                        tokenCalls.incrementAndGet();
                        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
                        var requestForm =
                                form(
                                        new String(
                                                exchange.getRequestBody().readAllBytes(),
                                                StandardCharsets.UTF_8));
                        lastForm = requestForm;
                        if (tokenDelayMillis > 0)
                            try {
                                Thread.sleep(tokenDelayMillis);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        var token = tokens.get(requestForm.get("code"));
                        var expectedVerifier = expectedVerifiers.get(requestForm.get("code"));
                        if (token == null
                                || (expectedVerifier != null
                                        && !expectedVerifier.equals(
                                                requestForm.get("code_verifier"))))
                            respond(exchange, 400, "{\"error\":\"invalid_grant\"}");
                        else
                            respond(
                                    exchange,
                                    200,
                                    new JsonMapper()
                                            .writeValueAsString(
                                                    Map.of(
                                                            "access_token",
                                                            "google-access",
                                                            "token_type",
                                                            "Bearer",
                                                            "expires_in",
                                                            3600,
                                                            "id_token",
                                                            token)));
                    });
            server.createContext(
                    "/jwks",
                    exchange -> {
                        keyCalls.incrementAndGet();
                        var key =
                                new RSAKey.Builder((RSAPublicKey) keys.getPublic())
                                        .keyID(kid)
                                        .algorithm(JWSAlgorithm.RS256)
                                        .build();
                        respond(exchange, 200, new JWKSet(key).toString());
                    });
            server.createContext(
                    "/",
                    exchange -> {
                        unexpectedCalls.incrementAndGet();
                        respond(exchange, 404, "{}");
                    });
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public GoogleOidcClient.Endpoints endpoints() {
        return new GoogleOidcClient.Endpoints(
                base() + "/authorize", base() + "/token", base() + "/jwks");
    }

    public JWTClaimsSet.Builder claims(String nonce, String subject) {
        var now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("https://accounts.google.com")
                .audience("test-client")
                .subject(subject)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .claim("nonce", nonce)
                .claim("name", "Test User")
                .claim("email", "test@example.com");
    }

    public String sign(JWTClaimsSet claims) {
        return sign(claims, keys, kid, null);
    }

    public String sign(
            JWTClaimsSet claims, java.security.KeyPair pair, String keyId, URI headerUrl) {
        try {
            var header = new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId);
            if (headerUrl != null) header.jwkURL(headerUrl);
            var jwt = new SignedJWT(header.build(), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) pair.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Map<String, String> form(String text) {
        Map<String, String> values = new HashMap<>();
        for (var part : text.split("&")) {
            var pair = part.split("=", 2);
            values.put(
                    URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "");
        }
        return values;
    }

    private void respond(HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        var bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        try {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        } finally {
            exchange.close();
        }
    }

    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
