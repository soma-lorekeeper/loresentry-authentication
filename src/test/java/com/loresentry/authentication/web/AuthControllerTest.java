package com.loresentry.authentication.web;

import com.loresentry.authentication.adapter.in.web.AuthController;
import com.loresentry.authentication.application.port.in.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import java.net.URI;
import java.time.Instant;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
class AuthControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean LoginUseCase login;
    @MockitoBean RefreshUseCase refresh;
    @MockitoBean RevokeUseCase revoke;
    final Instant atExpiry = Instant.parse("2026-09-17T12:15:00Z"), rtExpiry = Instant.parse("2026-10-01T12:00:00Z");
    final TokenPair pair = new TokenPair("access", atExpiry, "refresh", rtExpiry);
    @Test void prepareCallbackAndRefreshHaveExactShapeAndNeverSetBrowserCookies() throws Exception {
        when(login.prepare()).thenReturn(new LoginUseCase.PreparedLogin(URI.create("https://accounts.google.com/authorize"), "request", atExpiry));
        mvc.perform(post("/auth/oauth/google/prepare").contentType("application/json").content("{}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3)).andExpect(jsonPath("$.login_request_id").value("request"))
            .andExpect(jsonPath("$.expires_at").value(atExpiry.toString())).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(header().doesNotExist("Set-Cookie")).andExpect(jsonPath("$.login_request_consumed").doesNotExist());
        when(login.callback(any())).thenReturn(new LoginUseCase.LoginResult(pair, AuthFailure.Consumption.CONSUMED));
        mvc.perform(post("/auth/oauth/google/callback").contentType("application/json").content("{\"login_request_id\":\"request\",\"state\":\"state\",\"code\":\"code\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5)).andExpect(jsonPath("$.access_token").value("access"))
            .andExpect(jsonPath("$.refresh_expires_at").value(rtExpiry.toString())).andExpect(jsonPath("$.login_request_consumed").value(true))
            .andExpect(header().string("Cache-Control", "no-store")).andExpect(header().doesNotExist("Set-Cookie"));
        verify(login).callback(new LoginUseCase.Callback("request", "state", "code", null));
        when(refresh.refresh("rt")).thenReturn(pair);
        mvc.perform(post("/auth/tokens/refresh").contentType("application/json").content("{\"refresh_token\":\"rt\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(4)).andExpect(jsonPath("$.access_expires_at").value(atExpiry.toString()))
            .andExpect(jsonPath("$.login_request_consumed").doesNotExist()).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void revokeReturns204WithoutBody() throws Exception {
        mvc.perform(post("/auth/tokens/revoke").contentType("application/json").content("{\"refresh_token\":\"rt\"}"))
            .andExpect(status().isNoContent()).andExpect(content().string("")); verify(revoke).revoke("rt");
    }
    @Test void rejectsMissingMalformedOrConflictingBodyFieldsAndUrlOnlyCredentials() throws Exception {
        for (var body : java.util.List.of("{}", "[]", "{broken", "{\"refresh_token\":1}", "{\"refresh_token\":\"\"}"))
            mvc.perform(post("/auth/tokens/refresh").contentType("application/json").content(body)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST")).andExpect(jsonPath("$.login_request_consumed").doesNotExist());
        mvc.perform(post("/auth/tokens/refresh").queryParam("refresh_token", "secret").contentType("application/json").content("{}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/auth/oauth/google/callback").contentType("application/json").content("{\"login_request_id\":\"id\",\"state\":\"state\",\"code\":\"code\",\"error\":\"denied\"}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.login_request_consumed").value(false));
        mvc.perform(post("/auth/oauth/google/prepare").contentType("application/json").content("{\"redirect_uri\":\"https://evil.example\"}"))
            .andExpect(status().isBadRequest()); verifyNoInteractions(login, refresh, revoke);
    }
    @Test void callbackConsumptionAndRefreshFailureMeaningSurviveHttpMapping() throws Exception {
        when(login.callback(any())).thenThrow(new AuthFailure(AuthFailure.Reason.LOGIN_UNAVAILABLE, AuthFailure.Consumption.UNKNOWN));
        mvc.perform(post("/auth/oauth/google/callback").contentType("application/json").content("{\"login_request_id\":\"id\",\"state\":\"state\",\"code\":\"code\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.login_request_consumed").value(org.hamcrest.Matchers.nullValue()));
        when(refresh.refresh("rt")).thenThrow(new AuthFailure(AuthFailure.Reason.REFRESH_SAVE_FAILED));
        mvc.perform(post("/auth/tokens/refresh").contentType("application/json").content("{\"refresh_token\":\"rt\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("REFRESH_SAVE_FAILED"))
            .andExpect(jsonPath("$.next_action").value("RELOGIN")).andExpect(jsonPath("$.login_request_consumed").doesNotExist());
    }
}
