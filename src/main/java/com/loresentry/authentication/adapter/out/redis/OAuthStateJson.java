package com.loresentry.authentication.adapter.out.redis;

import lombok.RequiredArgsConstructor;
import com.loresentry.authentication.application.port.out.OAuthStateStore.State;
import com.loresentry.authentication.application.port.out.PortFailure;
import com.loresentry.authentication.domain.OAuthSecrets;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

@RequiredArgsConstructor
public final class OAuthStateJson {
    private static final Set<String> FIELDS = Set.of("schema_version", "registration_id", "client_id", "redirect_uri",
            "state", "nonce", "code_verifier", "created_at", "expires_at");
    private final JsonMapper mapper;
    public String encode(State value) {
        validate(value);
        return mapper.writeValueAsString(Map.of("schema_version", value.schemaVersion(),
            "registration_id", value.registrationId(), "client_id", value.clientId(), "redirect_uri", value.redirectUri(),
            "state", value.state(), "nonce", value.nonce(), "code_verifier", value.codeVerifier(),
            "created_at", value.createdAt().getEpochSecond(), "expires_at", value.expiresAt().getEpochSecond()));
    }
    public State decode(String json) {
        try {
            var n = mapper.readTree(json);
            if (!n.isObject() || !n.propertyNames().equals(FIELDS)) throw invalid();
            for (var field : Set.of("registration_id", "client_id", "redirect_uri", "state", "nonce", "code_verifier"))
                if (!n.get(field).isString()) throw invalid();
            for (var field : Set.of("schema_version", "created_at", "expires_at"))
                if (!n.get(field).isIntegralNumber() || !n.get(field).canConvertToLong()) throw invalid();
            if (n.get("schema_version").longValue() != 1) throw invalid();
            var value = new State(1, n.get("registration_id").asString(), n.get("client_id").asString(),
                n.get("redirect_uri").asString(), n.get("state").asString(), n.get("nonce").asString(),
                n.get("code_verifier").asString(), Instant.ofEpochSecond(n.get("created_at").longValue()),
                Instant.ofEpochSecond(n.get("expires_at").longValue()));
            validate(value);
            return value;
        } catch (RuntimeException e) { throw invalid(); }
    }
    private void validate(State v) {
        if (v == null || v.schemaVersion() != 1 || !"google".equals(v.registrationId())
            || v.clientId() == null || v.clientId().isBlank() || v.redirectUri() == null || v.redirectUri().isBlank()
            || !OAuthSecrets.valid(v.state()) || !OAuthSecrets.valid(v.nonce()) || !OAuthSecrets.valid(v.codeVerifier())
            || v.createdAt() == null || v.expiresAt() == null || !v.createdAt().plusSeconds(300).equals(v.expiresAt())) throw invalid();
    }
    private static PortFailure invalid() {
        return new PortFailure(PortFailure.Kind.INVALID_DATA, PortFailure.Execution.NOT_EXECUTED, false);
    }
}
