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
    final Instant prepareExpiry = Instant.parse("2026-09-17T12:15:00Z"),
            sessionExpiry = Instant.parse("2026-10-01T12:00:00Z");

    @Test
    void prepareAndCallbackHaveExactShapeAndNeverSetBrowserCookies() throws Exception {
        when(login.prepare())
                .thenReturn(
                        new LoginUseCase.PreparedLogin(
                                URI.create("https://accounts.google.com/authorize"),
                                "request",
                                prepareExpiry));
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
                .andExpect(jsonPath("$.expires_at").value(prepareExpiry.toString()))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andExpect(jsonPath("$.login_request_consumed").doesNotExist());
        when(login.callback(any()))
                .thenReturn(
                        new LoginUseCase.LoginResult(
                                new com.loresentry.authentication.domain.SessionId("A".repeat(43)),
                                sessionExpiry,
                                AuthFailure.Consumption.CONSUMED));
        mvc.perform(
                        post("/auth/oauth/google/callback")
                                .contentType("application/json")
                                .content(
                                        "{\"login_request_id\":\"request\",\"state\":\"state\",\"code\":\"code\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.session_id").value("A".repeat(43)))
                .andExpect(jsonPath("$.expires_at").value(sessionExpiry.toString()))
                .andExpect(jsonPath("$.login_request_consumed").value(true))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
        verify(login).callback(new LoginUseCase.Callback("request", "state", "code", null));
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
                                sessionExpiry,
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
                .andExpect(jsonPath("$.expires_at").value(sessionExpiry.toString()))
                .andExpect(
                        jsonPath("$.login_request_consumed")
                                .value(org.hamcrest.Matchers.equalTo(expected)));
        verify(login).callback(new LoginUseCase.Callback("request", "state", "code", null));
    }

    @Test
    void rejectsMissingMalformedOrConflictingBodyFieldsAndUrlOnlyCredentials() throws Exception {
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
        verifyNoInteractions(login);
    }

    @Test
    void callbackConsumptionSurvivesHttpMapping() throws Exception {
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
                                sessionExpiry,
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
