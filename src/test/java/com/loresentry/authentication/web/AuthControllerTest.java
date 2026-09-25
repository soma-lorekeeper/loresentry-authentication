package com.loresentry.authentication.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.loresentry.authentication.adapter.in.web.AuthController;
import com.loresentry.authentication.adapter.in.web.mapper.AuthRequestMapperImpl;
import com.loresentry.authentication.adapter.in.web.mapper.AuthResponseMapperImpl;
import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.config.JacksonConfiguration;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AuthController.class)
@Import({JacksonConfiguration.class, AuthResponseMapperImpl.class, AuthRequestMapperImpl.class})
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean LoginUseCase login;
    @MockitoBean RefreshUseCase refresh;
    @MockitoBean RevokeUseCase revoke;
    final Instant atExpiry = Instant.parse("2026-09-17T12:15:00Z"),
            rtExpiry = Instant.parse("2026-10-01T12:00:00Z");
    final TokenPair pair = new TokenPair("access", atExpiry, "refresh", rtExpiry);

    @Test
    void prepareCallbackAndRefreshHaveExactShapeAndNeverSetBrowserCookies() throws Exception {
        when(login.prepare())
                .thenReturn(
                        new LoginUseCase.PreparedLogin(
                                URI.create("https://accounts.google.com/authorize"),
                                "request",
                                atExpiry));
        mvc.perform(
                        post("/auth/oauth/google/prepare")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.login_request_id").value("request"))
                .andExpect(
                        jsonPath("$.authorization_url")
                                .value("https://accounts.google.com/authorize"))
                .andExpect(jsonPath("$.expires_at").value(atExpiry.toString()))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.login_request_consumed").doesNotExist());
        when(login.callback(any()))
                .thenReturn(
                        new LoginUseCase.LoginResult(
                                new com.loresentry.authentication.domain.SessionId("A".repeat(43)),
                                rtExpiry,
                                AuthFailure.Consumption.CONSUMED));
        mvc.perform(
                        post("/auth/oauth/google/callback")
                                .contentType("application/json")
                                .content(
                                        "{\"login_request_id\":\"request\",\"state\":\"state\",\"code\":\"code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.session_id").value("A".repeat(43)))
                .andExpect(jsonPath("$.expires_at").value(rtExpiry.toString()))
                .andExpect(jsonPath("$.login_request_consumed").value(true))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(login).callback(new LoginUseCase.Callback("request", "state", "code", null));
        when(refresh.refresh("rt")).thenReturn(pair);
        mvc.perform(
                        post("/auth/tokens/refresh")
                                .contentType("application/json")
                                .content("{\"refresh_token\":\"rt\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(4))
                .andExpect(jsonPath("$.access_expires_at").value(atExpiry.toString()))
                .andExpect(jsonPath("$.access_token").value("access"))
                .andExpect(jsonPath("$.refresh_token").value("refresh"))
                .andExpect(jsonPath("$.refresh_expires_at").value(rtExpiry.toString()))
                .andExpect(jsonPath("$.login_request_consumed").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void revokeReturns204WithoutBody() throws Exception {
        mvc.perform(
                        post("/auth/tokens/revoke")
                                .contentType("application/json")
                                .content("{\"refresh_token\":\"rt\"}"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));
        verify(revoke).revoke("rt");
    }

    @ParameterizedTest
    @CsvSource(
            value = {"CONSUMED,true", "NOT_CONSUMED,false", "UNKNOWN,NULL"},
            nullValues = "NULL")
    void callbackMapsSessionAndAllConsumptionStates(
            AuthFailure.Consumption consumption, Boolean expected) throws Exception {
        when(login.callback(any()))
                .thenReturn(
                        new LoginUseCase.LoginResult(
                                new com.loresentry.authentication.domain.SessionId("A".repeat(43)),
                                rtExpiry,
                                consumption));
        mvc.perform(
                        post("/auth/oauth/google/callback")
                                .contentType("application/json")
                                .content(
                                        "{\"login_request_id\":\"request\",\"state\":\"state\",\"code\":\"code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.session_id").value("A".repeat(43)))
                .andExpect(jsonPath("$.access_token").doesNotExist())
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andExpect(jsonPath("$.expires_at").value(rtExpiry.toString()))
                .andExpect(
                        jsonPath("$.login_request_consumed")
                                .value(org.hamcrest.Matchers.equalTo(expected)));
        verify(login).callback(new LoginUseCase.Callback("request", "state", "code", null));
    }

    @Test
    void rejectsMissingMalformedOrConflictingBodyFieldsAndUrlOnlyCredentials() throws Exception {
        for (var body :
                java.util.List.of(
                        "{}", "[]", "{broken", "{\"refresh_token\":1}", "{\"refresh_token\":\"\"}"))
            mvc.perform(post("/auth/tokens/refresh").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.login_request_consumed").doesNotExist());
        mvc.perform(
                        post("/auth/tokens/refresh")
                                .queryParam("refresh_token", "secret")
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(
                        post("/auth/oauth/google/callback")
                                .contentType("application/json")
                                .content(
                                        "{\"login_request_id\":\"id\",\"state\":\"state\",\"code\":\"code\",\"error\":\"denied\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.login_request_consumed").value(false));
        mvc.perform(
                        post("/auth/oauth/google/prepare")
                                .contentType("application/json")
                                .content("{\"redirect_uri\":\"https://evil.example\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(login, refresh, revoke);
    }

    @Test
    void callbackConsumptionAndRefreshFailureMeaningSurviveHttpMapping() throws Exception {
        when(login.callback(any()))
                .thenThrow(
                        new AuthFailure(
                                AuthFailure.Reason.LOGIN_UNAVAILABLE,
                                AuthFailure.Consumption.UNKNOWN));
        mvc.perform(
                        post("/auth/oauth/google/callback")
                                .contentType("application/json")
                                .content(
                                        "{\"login_request_id\":\"id\",\"state\":\"state\",\"code\":\"code\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(
                        jsonPath("$.login_request_consumed")
                                .value(org.hamcrest.Matchers.nullValue()));
        when(refresh.refresh("rt"))
                .thenThrow(new AuthFailure(AuthFailure.Reason.REFRESH_OUTCOME_UNKNOWN));
        mvc.perform(
                        post("/auth/tokens/refresh")
                                .contentType("application/json")
                                .content("{\"refresh_token\":\"rt\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("REFRESH_OUTCOME_UNKNOWN"))
                .andExpect(jsonPath("$.next_action").value("RELOGIN"))
                .andExpect(jsonPath("$.login_request_consumed").doesNotExist());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "null",
                "[]",
                "\"token\"",
                "{\"refresh_token\":null}",
                "{\"refresh_token\":123}",
                "{\"refresh_token\":1.5}",
                "{\"refresh_token\":true}",
                "{\"refresh_token\":[]}",
                "{\"refresh_token\":{}}",
                "{\"refresh_token\":\"\"}",
                "{\"refresh_token\":\"　\"}",
                "{\"refreshToken\":\"rt\"}",
                "{\"refresh_token\":\"rt\",\"extra\":null}"
            })
    void tokenEndpointsRejectInvalidTypesBlankTokensAndUnknownFields(String body) throws Exception {
        for (var action : java.util.List.of("refresh", "revoke")) {
            mvc.perform(
                            post("/auth/tokens/" + action)
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.login_request_consumed").doesNotExist());
        }
        verifyNoInteractions(login, refresh, revoke);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "\"\"", "true", "{\"extra\":null}"})
    void prepareRequiresAnEmptyObject(String body) throws Exception {
        mvc.perform(
                        post("/auth/oauth/google/prepare")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(login);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"code\":null}",
                "{\"code\":\"\"}",
                "{\"code\":\"　\"}",
                "{\"code\":123}",
                "{\"error\":false}",
                "{\"code\":\"code\",\"error\":\"\"}",
                "{\"code\":\"code\",\"error\":\"denied\"}",
                "{\"code\":\"code\",\"validOutcome\":true}",
                "{\"code\":\"code\",\"extra\":null}"
            })
    void callbackRequiresExactlyOneNonblankStringOutcome(String outcome) throws Exception {
        String body =
                "{\"login_request_id\":\"id\",\"state\":\"state\""
                        + (outcome.equals("{}") ? "}" : "," + outcome.substring(1));
        mvc.perform(
                        post("/auth/oauth/google/callback")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.login_request_consumed").value(false));
        verifyNoInteractions(login);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"login_request_id\":null,\"state\":\"state\"}",
                "{\"login_request_id\":\"id\",\"state\":\"　\"}",
                "{\"login_request_id\":42,\"state\":\"state\"}",
                "{\"login_request_id\":\"id\",\"state\":false}"
            })
    void callbackRejectsMissingBlankOrCoercedIdentifiersBeforeConsumption(String identifiers)
            throws Exception {
        String body =
                "{\"code\":\"code\""
                        + (identifiers.equals("{}") ? "}" : "," + identifiers.substring(1));
        mvc.perform(
                        post("/auth/oauth/google/callback")
                                .contentType("application/json")
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.login_request_consumed").value(false));
        verifyNoInteractions(login);
    }

    @Test
    void callbackAcceptsExplicitNullAlternativeAndProviderDenial() throws Exception {
        when(login.callback(any()))
                .thenReturn(
                        new LoginUseCase.LoginResult(
                                new com.loresentry.authentication.domain.SessionId("A".repeat(43)),
                                rtExpiry,
                                AuthFailure.Consumption.UNKNOWN));
        mvc.perform(
                        post("/auth/oauth/google/callback")
                                .contentType("application/json")
                                .content(
                                        "{\"login_request_id\":\"id\",\"state\":\"state\",\"code\":\"code\",\"error\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(
                        jsonPath("$.login_request_consumed")
                                .value(org.hamcrest.Matchers.nullValue()));
        verify(login).callback(new LoginUseCase.Callback("id", "state", "code", null));

        when(login.callback(any()))
                .thenThrow(
                        new AuthFailure(
                                AuthFailure.Reason.OAUTH_LOGIN_DENIED,
                                AuthFailure.Consumption.CONSUMED));
        mvc.perform(
                        post("/auth/oauth/google/callback")
                                .contentType("application/json")
                                .content(
                                        "{\"login_request_id\":\"id\",\"state\":\"state\",\"code\":null,\"error\":\"access_denied\"}"))
                .andExpect(jsonPath("$.code").value("OAUTH_LOGIN_DENIED"))
                .andExpect(jsonPath("$.login_request_consumed").value(true));
        verify(login).callback(new LoginUseCase.Callback("id", "state", null, "access_denied"));
    }
}
