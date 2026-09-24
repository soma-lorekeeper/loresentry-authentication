package com.loresentry.authentication.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.loresentry.authentication.adapter.in.web.AccountController;
import com.loresentry.authentication.adapter.in.web.mapper.AuthResponseMapperImpl;
import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.config.JacksonConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AccountController.class)
@Import({JacksonConfiguration.class, AuthResponseMapperImpl.class})
class AccountControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean AccountUseCase accounts;
    final UUID id = UUID.randomUUID();

    @Test
    void caseInsensitiveHeaderReturnsNullableEmailAndRenamesOnlyDisplayName() throws Exception {
        when(accounts.get(id)).thenReturn(new AccountUseCase.Profile(id, "Name", null));
        mvc.perform(get("/auth/users/me").header("x-uSeR-iD", id.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.display_name").value("Name"))
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue()));
        when(accounts.rename(id, "New Name"))
                .thenReturn(new AccountUseCase.Profile(id, "New Name", "test@example.com"));
        mvc.perform(
                        patch("/auth/users/me")
                                .header("X-User-Id", id.toString())
                                .contentType("application/json")
                                .content("{\"display_name\":\"New Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("New Name"))
                .andExpect(jsonPath("$.email").value("test@example.com"));
        verify(accounts).rename(id, "New Name");
    }

    @Test
    void missingDuplicateAndMalformedUserHeadersAreRejected() throws Exception {
        mvc.perform(get("/auth/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"));
        mvc.perform(
                        get("/auth/users/me")
                                .header("X-User-Id", id.toString())
                                .header("x-user-id", id.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        for (var value : java.util.List.of("", "invalid", "1-1-1-1-1", id + "," + id, " " + id))
            mvc.perform(get("/auth/users/me").header("X-User-Id", value))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(accounts);
    }

    @Test
    void refusesUnknownModificationFields() throws Exception {
        for (var body :
                java.util.List.of(
                        "{}",
                        "null",
                        "[]",
                        "{\"display_name\":null}",
                        "{\"display_name\":1}",
                        "{\"display_name\":1.5}",
                        "{\"display_name\":true}",
                        "{\"display_name\":[]}",
                        "{\"display_name\":{}}",
                        "{\"displayName\":\"Name\"}",
                        "{\"display_name\":\"Name\",\"email\":\"evil@example.com\"}",
                        "{\"display_name\":\"Name\",\"id\":\"" + id + "\"}"))
            mvc.perform(
                            patch("/auth/users/me")
                                    .header("X-User-Id", id.toString())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(accounts);
    }

    @Test
    void coreFailuresKeepTheirContract() throws Exception {
        when(accounts.get(id))
                .thenThrow(
                        new AuthFailure(AuthFailure.Reason.USER_NOT_FOUND),
                        new AuthFailure(AuthFailure.Reason.ACCOUNT_UNAVAILABLE));
        mvc.perform(get("/auth/users/me").header("X-User-Id", id.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.next_action").value("RELOGIN"));
        mvc.perform(get("/auth/users/me").header("X-User-Id", id.toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_UNAVAILABLE"))
                .andExpect(jsonPath("$.next_action").value("RETRY_LATER"));
        when(accounts.rename(id, " "))
                .thenThrow(new AuthFailure(AuthFailure.Reason.INVALID_DISPLAY_NAME));
        mvc.perform(
                        patch("/auth/users/me")
                                .header("X-User-Id", id.toString())
                                .contentType("application/json")
                                .content("{\"display_name\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DISPLAY_NAME"));
    }
}
