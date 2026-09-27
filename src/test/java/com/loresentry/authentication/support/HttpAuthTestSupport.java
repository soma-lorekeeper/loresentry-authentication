package com.loresentry.authentication.support;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.adapter.out.google.*;
import com.loresentry.authentication.application.port.out.*;
import java.net.URI;
import java.net.http.*;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public abstract class HttpAuthTestSupport extends DatabaseTestSupport {
    @TestConfiguration(proxyBeanMethods = false)
    public static class GoogleHttpConfiguration {
        @Bean(destroyMethod = "close")
        MockGoogle mockGoogle() {
            return new MockGoogle();
        }

        @Bean(destroyMethod = "close")
        @Primary
        GoogleOidcClient controlledGoogleClient(
                GoogleSettings settings, Clock clock, MockGoogle google) {
            return new GoogleOidcClient(settings, clock, google.endpoints());
        }
    }

    @LocalServerPort int port;
    @Autowired protected JsonMapper mapper;
    @Autowired protected MockGoogle google;
    @Autowired protected AccountStore accounts;
    @Autowired protected OAuthStateStore states;

    protected record Result(int status, JsonNode body, HttpHeaders headers) {}

    protected record Pending(String id, String state, String code) {
        public Map<String, String> body() {
            return Map.of("login_request_id", id, "state", state, "code", code);
        }
    }

    protected Result call(String method, String path, Object body, UUID user) {
        try (var client = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path));
            if (user != null) request.header("X-User-Id", user.toString());
            if (body != null) request.header("Content-Type", "application/json");
            request.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
            var response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Result(
                    response.statusCode(),
                    response.body().isEmpty() ? null : mapper.readTree(response.body()),
                    response.headers());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    protected Pending pending(String subject) {
        var prepared = call("POST", "/auth/oauth/google/prepare", Map.of(), null);
        assertThat(prepared.status()).isEqualTo(200);
        var id = prepared.body().get("login_request_id").asString();
        var parameters =
                MockGoogle.form(
                        URI.create(prepared.body().get("authorization_url").asString())
                                .getRawQuery());
        var code = UUID.randomUUID().toString();
        google.expectedVerifiers.put(code, states.find(id).orElseThrow().codeVerifier());
        google.tokens.put(
                code, google.sign(google.claims(parameters.get("nonce"), subject).build()));
        return new Pending(id, parameters.get("state"), code);
    }

    protected Result callback(Pending pending) {
        return call("POST", "/auth/oauth/google/callback", pending.body(), null);
    }

    protected Result login(String subject) {
        var result = callback(pending(subject));
        assertThat(result.status()).isEqualTo(200);
        return result;
    }

    protected void error(Result result, int status, String code, String nextAction) {
        assertThat(result.status()).isEqualTo(status);
        assertThat(result.body().get("code").asString()).isEqualTo(code);
        assertThat(result.body().get("next_action").asString()).isEqualTo(nextAction);
        assertThat(result.body().has("access_token")).isFalse();
        assertThat(result.body().has("refresh_token")).isFalse();
    }
}
