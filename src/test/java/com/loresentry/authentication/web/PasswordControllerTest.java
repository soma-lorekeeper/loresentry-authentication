package com.loresentry.authentication.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.loresentry.authentication.adapter.in.web.PasswordController;
import com.loresentry.authentication.adapter.in.web.mapper.AuthRequestMapperImpl;
import com.loresentry.authentication.adapter.in.web.mapper.AuthResponseMapperImpl;
import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.config.JacksonConfiguration;
import com.loresentry.authentication.domain.ConsentId;
import com.loresentry.authentication.domain.SessionId;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PasswordController.class)
@Import({JacksonConfiguration.class, AuthResponseMapperImpl.class, AuthRequestMapperImpl.class})
class PasswordControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean PasswordAuthUseCase passwords;
    final Instant expiry = Instant.parse("2026-11-21T00:00:00Z");

    @Test
    void signUpReturnsOnlyTheSessionFieldsAndNoCookies() throws Exception {
        when(passwords.signUp(new PasswordAuthUseCase.SignUp("judge1", "correct-horse-7", "심사")))
                .thenReturn(
                        PasswordAuthUseCase.Result.authenticated(
                                new SessionId("A".repeat(43)), expiry));
        mvc.perform(
                        post("/auth/password/signup")
                                .contentType("application/json")
                                .content(
                                        "{\"login_id\":\"judge1\",\"password\":\"correct-horse-7\",\"display_name\":\"심사\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.status").value("AUTHENTICATED"))
                .andExpect(jsonPath("$.session_id").value("A".repeat(43)))
                .andExpect(jsonPath("$.expires_at").value(expiry.toString()))
                .andExpect(jsonPath("$.consent_request_id").doesNotExist())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void loginReturnsTermsRequiredWithoutASession() throws Exception {
        when(passwords.login(new PasswordAuthUseCase.Login("judge1", "correct-horse-7")))
                .thenReturn(
                        PasswordAuthUseCase.Result.termsRequired(
                                new ConsentId("B".repeat(42) + "A"), expiry));
        mvc.perform(
                        post("/auth/password/login")
                                .contentType("application/json")
                                .content(
                                        "{\"login_id\":\"judge1\",\"password\":\"correct-horse-7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.status").value("TERMS_REQUIRED"))
                .andExpect(jsonPath("$.consent_request_id").value("B".repeat(42) + "A"))
                .andExpect(jsonPath("$.session_id").doesNotExist());
    }

    @Test
    void domainFailuresUseTheErrorContract() throws Exception {
        when(passwords.signUp(any())).thenThrow(new AuthFailure(AuthFailure.Reason.LOGIN_ID_TAKEN));
        mvc.perform(
                        post("/auth/password/signup")
                                .contentType("application/json")
                                .content(
                                        "{\"login_id\":\"judge1\",\"password\":\"correct-horse-7\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("LOGIN_ID_TAKEN"))
                .andExpect(jsonPath("$.next_action").value("NONE"))
                .andExpect(jsonPath("$.login_request_consumed").doesNotExist());
        when(passwords.login(any()))
                .thenThrow(new AuthFailure(AuthFailure.Reason.INVALID_CREDENTIALS));
        mvc.perform(
                        post("/auth/password/login")
                                .contentType("application/json")
                                .content("{\"login_id\":\"judge1\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "{}",
                "{\"login_id\":\"judge1\"}",
                "{\"password\":\"correct-horse-7\"}",
                "{\"login_id\":1,\"password\":\"correct-horse-7\"}",
                "{\"login_id\":\"judge1\",\"password\":\"correct-horse-7\",\"email\":\"x@y\"}"
            })
    void malformedBodiesAreInvalidRequests(String body) throws Exception {
        mvc.perform(post("/auth/password/login").contentType("application/json").content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(passwords);
    }
}
