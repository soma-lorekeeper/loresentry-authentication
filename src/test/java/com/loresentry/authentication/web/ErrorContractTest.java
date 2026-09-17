package com.loresentry.authentication.web;

import com.loresentry.authentication.adapter.in.web.*;
import com.loresentry.authentication.application.port.in.AuthFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.*;
import org.springframework.mock.web.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(OutputCaptureExtension.class)
class ErrorContractTest {
    @RestController static class Fixture {
        @PostMapping("/auth/oauth/google/callback") void callback(@RequestBody Map<String, Object> body) {
            throw new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE, AuthFailure.Consumption.valueOf((String) body.get("outcome")));
        }
        @GetMapping("/auth/test") void unexpected() { throw new IllegalArgumentException("secret-token oauth-code SELECT password client-secret provider-body"); }
    }
    MockMvc mvc() { return MockMvcBuilders.standaloneSetup(new Fixture()).setControllerAdvice(new AuthExceptionHandler()).build(); }
    @Test void everyDocumentedCodeHasExpectedStatusAndAction() {
        Map<AuthFailure.Reason, String> expected = Map.ofEntries(
            entry("INVALID_REQUEST","400 NONE"), entry("INVALID_DISPLAY_NAME","400 NONE"),
            entry("OAUTH_REQUEST_INVALID","400 RESTART_LOGIN"), entry("OAUTH_LOGIN_DENIED","400 RESTART_LOGIN"),
            entry("OAUTH_IDENTITY_INVALID","401 RESTART_LOGIN"), entry("REFRESH_REJECTED","401 RELOGIN"),
            entry("INVALID_REFRESH_TOKEN","401 NONE"), entry("USER_CONTEXT_REQUIRED","401 RELOGIN"), entry("USER_NOT_FOUND","404 RELOGIN"),
            entry("LOGIN_UNAVAILABLE","503 RESTART_LOGIN"), entry("REFRESH_UNAVAILABLE","503 RETRY_LATER"),
            entry("REFRESH_OUTCOME_UNKNOWN","503 RELOGIN"), entry("REFRESH_SAVE_FAILED","503 RELOGIN"),
            entry("REVOCATION_UNCONFIRMED","503 NONE"), entry("ACCOUNT_UNAVAILABLE","503 RETRY_LATER"), entry("INTERNAL_ERROR","500 NONE"));
        assertThat(expected).hasSize(AuthFailure.Reason.values().length);
        for (var reason : AuthFailure.Reason.values()) {
            var request = new MockHttpServletRequest("POST", "/auth/tokens/refresh");
            var response = ErrorResponses.response(new AuthFailure(reason), request);
            assertThat(response.getStatusCode().value()+" "+response.getBody().get("next_action")).isEqualTo(expected.get(reason));
            assertThat(response.getBody()).containsOnlyKeys("code", "message", "next_action").containsEntry("code", reason.name());
            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        }
    }
    @Test void callbackPreservesAllThreeOutcomesAndMalformedJsonIsConfirmedNotConsumed() throws Exception {
        var mvc = mvc();
        for (var outcome : AuthFailure.Consumption.values()) {
            var result = mvc.perform(post("/auth/oauth/google/callback").contentType("application/json").content("{\"outcome\":\""+outcome+"\"}"))
                .andExpect(status().isServiceUnavailable()).andReturn();
            var body = new JsonMapper().readTree(result.getResponse().getContentAsString());
            assertThat(body.has("login_request_consumed")).isTrue();
            if (outcome == AuthFailure.Consumption.UNKNOWN) assertThat(body.get("login_request_consumed").isNull()).isTrue();
            else assertThat(body.get("login_request_consumed").booleanValue()).isEqualTo(outcome == AuthFailure.Consumption.CONSUMED);
        }
        mvc.perform(post("/auth/oauth/google/callback").contentType("application/json").content("{secret-token"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.login_request_consumed").value(false));
    }
    @Test void unexpectedAndPreMvcFailuresUseSameSafeResponseAndLogs(CapturedOutput output) throws Exception {
        var response = mvc().perform(get("/auth/test")).andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(jsonPath("$.login_request_consumed").doesNotExist()).andReturn().getResponse();
        var filter = new ErrorBoundaryFilter(new JsonMapper());
        var request = new MockHttpServletRequest("POST", "/auth/oauth/google/callback"); var filtered = new MockHttpServletResponse();
        filter.doFilter(request, filtered, (req, res) -> { throw new jakarta.servlet.ServletException("secret-token oauth-code SELECT password client-secret provider-body"); });
        assertThat(filtered.getStatus()).isEqualTo(500);
        var parsed = new JsonMapper().readTree(filtered.getContentAsString());
        assertThat(parsed.get("code").asString()).isEqualTo("INTERNAL_ERROR"); assertThat(parsed.get("login_request_consumed").isNull()).isTrue();
        for (String value : List.of("secret-token", "oauth-code", "SELECT password", "client-secret", "provider-body")) {
            assertThat(response.getContentAsString()).doesNotContain(value); assertThat(filtered.getContentAsString()).doesNotContain(value);
            assertThat(output.getAll()).doesNotContain(value);
        }
        assertThat(output.getAll()).contains("java.lang.IllegalArgumentException", "jakarta.servlet.ServletException", "ErrorContractTest");
    }
    private Map.Entry<AuthFailure.Reason, String> entry(String code, String value) { return Map.entry(AuthFailure.Reason.valueOf(code), value); }
}
