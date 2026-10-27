package com.loresentry.authentication.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.loresentry.authentication.adapter.in.web.AccountController;
import com.loresentry.authentication.adapter.in.web.mapper.AuthResponseMapperImpl;
import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.config.JacksonConfiguration;
import com.loresentry.authentication.domain.Acquisition;
import com.loresentry.authentication.domain.SupportedLocale;
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
        when(accounts.get(id))
                .thenReturn(new AccountUseCase.Profile(id, "Name", null, false, null));
        mvc.perform(get("/auth/users/me").header("x-uSeR-iD", id.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.display_name").value("Name"))
                .andExpect(jsonPath("$.email").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.onboarding_completed").value(false))
                .andExpect(jsonPath("$.locale").value(org.hamcrest.Matchers.nullValue()));
        when(accounts.rename(id, "New Name"))
                .thenReturn(
                        new AccountUseCase.Profile(
                                id, "New Name", "test@example.com", true, SupportedLocale.KO));
        mvc.perform(
                        patch("/auth/users/me")
                                .header("X-User-Id", id.toString())
                                .contentType("application/json")
                                .content("{\"display_name\":\"New Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.display_name").value("New Name"))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.onboarding_completed").value(true))
                .andExpect(jsonPath("$.locale").value("ko"));
        verify(accounts).rename(id, "New Name");
    }

    @Test
    void localeChangeReturnsTheProfileWithoutCaching() throws Exception {
        when(accounts.changeLocale(id, "en"))
                .thenReturn(
                        new AccountUseCase.Profile(
                                id, "Name", "test@example.com", false, SupportedLocale.EN));
        mvc.perform(
                        put("/auth/users/me/locale")
                                .header("X-User-Id", id.toString())
                                .contentType("application/json")
                                .content("{\"locale\":\"en\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.display_name").value("Name"))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.onboarding_completed").value(false))
                .andExpect(jsonPath("$.locale").value("en"));
        verify(accounts).changeLocale(id, "en");
    }

    @Test
    void malformedLocaleBodiesAreInvalidRequests() throws Exception {
        for (var body :
                java.util.List.of(
                        "",
                        "{}",
                        "null",
                        "[]",
                        "\"en\"",
                        "{\"locale\":null}",
                        "{\"locale\":1}",
                        "{\"locale\":true}",
                        "{\"locale\":[\"en\"]}",
                        "{\"locale\":{}}",
                        "{\"language\":\"en\"}",
                        "{\"locale\":\"en\",\"display_name\":\"Name\"}"))
            mvc.perform(
                            put("/auth/users/me/locale")
                                    .header("X-User-Id", id.toString())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.next_action").value("NONE"));
        mvc.perform(put("/auth/users/me/locale").header("X-User-Id", id.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(accounts);
    }

    @Test
    void localeChangeKeepsTheUserHeaderAndCoreFailureContracts() throws Exception {
        mvc.perform(
                        put("/auth/users/me/locale")
                                .contentType("application/json")
                                .content("{\"locale\":\"en\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"));
        mvc.perform(
                        put("/auth/users/me/locale")
                                .header("X-User-Id", "invalid")
                                .contentType("application/json")
                                .content("{\"locale\":\"en\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(accounts);
        when(accounts.changeLocale(id, "fr"))
                .thenThrow(new AuthFailure(AuthFailure.Reason.INVALID_REQUEST));
        when(accounts.changeLocale(id, "ko"))
                .thenThrow(
                        new AuthFailure(AuthFailure.Reason.USER_NOT_FOUND),
                        new AuthFailure(AuthFailure.Reason.ACCOUNT_UNAVAILABLE));
        for (var expected :
                java.util.List.of(
                        new String[] {"fr", "400", "INVALID_REQUEST"},
                        new String[] {"ko", "404", "USER_NOT_FOUND"},
                        new String[] {"ko", "503", "ACCOUNT_UNAVAILABLE"}))
            mvc.perform(
                            put("/auth/users/me/locale")
                                    .header("X-User-Id", id.toString())
                                    .contentType("application/json")
                                    .content("{\"locale\":\"" + expected[0] + "\"}"))
                    .andExpect(status().is(Integer.parseInt(expected[1])))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value(expected[2]));
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

    @Test
    void onboardingCompletionAndDeletionReturnNoContentWithoutCaching() throws Exception {
        mvc.perform(put("/auth/users/me/onboarding").header("X-User-Id", id.toString()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        verify(accounts).completeOnboarding(id);
        mvc.perform(delete("/auth/users/me").header("X-User-Id", id.toString()))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(""));
        verify(accounts).delete(id);
    }

    @Test
    void onboardingAndDeletionRequireOneValidUserHeader() throws Exception {
        for (var request :
                java.util.List.of(put("/auth/users/me/onboarding"), delete("/auth/users/me")))
            mvc.perform(request)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"));
        for (var request :
                java.util.List.of(
                        put("/auth/users/me/onboarding").header("X-User-Id", "invalid"),
                        delete("/auth/users/me").header("X-User-Id", "invalid"),
                        delete("/auth/users/me")
                                .header("X-User-Id", id.toString())
                                .header("x-user-id", id.toString())))
            mvc.perform(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(post("/auth/users/me/onboarding").header("X-User-Id", id.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        verifyNoInteractions(accounts);
    }

    @Test
    void onboardingAndDeletionFailuresKeepTheirContract() throws Exception {
        doThrow(new AuthFailure(AuthFailure.Reason.USER_NOT_FOUND))
                .when(accounts)
                .completeOnboarding(id);
        mvc.perform(put("/auth/users/me/onboarding").header("X-User-Id", id.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(header().string("Cache-Control", "no-store"));
        doThrow(
                        new AuthFailure(AuthFailure.Reason.USER_NOT_FOUND),
                        new AuthFailure(AuthFailure.Reason.ACCOUNT_UNAVAILABLE))
                .when(accounts)
                .delete(id);
        mvc.perform(delete("/auth/users/me").header("X-User-Id", id.toString()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.next_action").value("RELOGIN"));
        mvc.perform(delete("/auth/users/me").header("X-User-Id", id.toString()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_UNAVAILABLE"))
                .andExpect(jsonPath("$.next_action").value("RETRY_LATER"))
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    void acquisitionPassesTheNormalizedSourceAndAnswersWithoutBody() throws Exception {
        mvc.perform(
                        put("/auth/users/me/acquisition")
                                .header("X-User-Id", id.toString())
                                .contentType("application/json")
                                .content(
                                        """
                                        {"utm_source":"google","utm_medium":"cpc","utm_campaign":"123",
                                         "utm_term":" 소설 에디터 ","utm_content":"","click_id":"Cj0K-a_1",
                                         "click_id_type":"gclid","referrer_host":"www.google.com",
                                         "landing_path":"/en/","landed_at":"2026-10-27T01:02:03.456Z"}
                                        """))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""))
                .andExpect(header().string("Cache-Control", "no-store"));
        verify(accounts)
                .recordAcquisition(
                        id,
                        new Acquisition(
                                "google",
                                "cpc",
                                "123",
                                "소설 에디터",
                                null,
                                "Cj0K-a_1",
                                "gclid",
                                "www.google.com",
                                "/en/",
                                java.time.Instant.parse("2026-10-27T01:02:03.456Z")));
        mvc.perform(
                        put("/auth/users/me/acquisition")
                                .header("X-User-Id", id.toString())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isNoContent());
        verify(accounts)
                .recordAcquisition(
                        id,
                        new Acquisition(
                                null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void malformedAcquisitionBodiesAreInvalidRequests() throws Exception {
        for (var body :
                java.util.List.of(
                        "",
                        "null",
                        "[]",
                        "{\"utm_source\":1}",
                        "{\"utm_source\":\"" + "a".repeat(101) + "\"}",
                        "{\"utm_term\":\"" + "a".repeat(201) + "\"}",
                        "{\"utm_source\":\"goo\\ngle\"}",
                        "{\"click_id\":\"abc\"}",
                        "{\"click_id_type\":\"gclid\"}",
                        "{\"click_id\":\"a b\",\"click_id_type\":\"gclid\"}",
                        "{\"click_id\":\"abc\",\"click_id_type\":\"fbclid\"}",
                        "{\"click_id\":\"\",\"click_id_type\":\"gclid\"}",
                        "{\"referrer_host\":\"WWW.GOOGLE.COM\"}",
                        "{\"referrer_host\":\"https://www.google.com/\"}",
                        "{\"referrer_host\":\"\"}",
                        "{\"landing_path\":\"en/\"}",
                        "{\"landing_path\":\"/?gclid=abc\"}",
                        "{\"landing_path\":\"/#top\"}",
                        "{\"landed_at\":\"yesterday\"}",
                        "{\"gclid\":\"abc\"}"))
            mvc.perform(
                            put("/auth/users/me/acquisition")
                                    .header("X-User-Id", id.toString())
                                    .contentType("application/json")
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.next_action").value("NONE"));
        verifyNoInteractions(accounts);
    }

    @Test
    void acquisitionKeepsTheUserHeaderAndCoreFailureContracts() throws Exception {
        mvc.perform(put("/auth/users/me/acquisition").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("USER_CONTEXT_REQUIRED"));
        doThrow(
                        new AuthFailure(AuthFailure.Reason.USER_NOT_FOUND),
                        new AuthFailure(AuthFailure.Reason.ACCOUNT_UNAVAILABLE))
                .when(accounts)
                .recordAcquisition(eq(id), any());
        mvc.perform(
                        put("/auth/users/me/acquisition")
                                .header("X-User-Id", id.toString())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
                .andExpect(jsonPath("$.next_action").value("RELOGIN"));
        mvc.perform(
                        put("/auth/users/me/acquisition")
                                .header("X-User-Id", id.toString())
                                .contentType("application/json")
                                .content("{}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("ACCOUNT_UNAVAILABLE"))
                .andExpect(jsonPath("$.next_action").value("RETRY_LATER"));
    }
}
