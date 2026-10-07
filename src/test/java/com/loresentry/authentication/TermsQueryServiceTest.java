package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.adapter.out.id.SecureSessionIds;
import com.loresentry.authentication.application.port.in.AuthFailure;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.application.service.TermsQueryService;
import com.loresentry.authentication.domain.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class TermsQueryServiceTest {
    final ConsentRequestStore requests = mock(ConsentRequestStore.class);
    final TermsVersionStore versions = mock(TermsVersionStore.class);
    final AccountStore accounts = mock(AccountStore.class);
    final Instant now = Instant.parse("2026-10-01T00:00:00Z");
    final UUID user = UUID.randomUUID();
    final ConsentId id = new ConsentId(new SecureSessionIds().generate().value());
    final TermsVersion current = new TermsVersion(UUID.randomUUID(), "v0", "약관", "원문", now, now);
    final TermsText english = new TermsText(SupportedLocale.EN, "Terms", "Translation");
    final TermsQueryService service =
            new TermsQueryService(requests, versions, accounts, Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach
    void setup() {
        when(requests.find(id))
                .thenReturn(
                        Optional.of(
                                new ConsentRequestStore.Pending(
                                        user, UUID.randomUUID(), now, now.plusSeconds(1800))));
        when(accounts.findById(user))
                .thenReturn(
                        Optional.of(
                                new AccountStore.Account(
                                        new User(user, "Name", now, now, null, null),
                                        new OAuthIdentity("google", "subject", user, null))));
        when(versions.current(now)).thenReturn(Optional.of(current));
        when(requests.refreshVersion(id, user, current.id()))
                .thenReturn(
                        Optional.of(
                                new ConsentRequestStore.Pending(
                                        user, current.id(), now, now.plusSeconds(1800))));
    }

    @Test
    void englishUsesTheTranslationButKeepsTheConsentTargetVersion() {
        when(versions.translation(current.id(), SupportedLocale.EN))
                .thenReturn(Optional.of(english));
        var view = service.query(id.value(), "en");
        assertThat(view.text()).isEqualTo(english);
        assertThat(view.terms()).isEqualTo(current);
        assertThat(view.expiresAt()).isEqualTo(now.plusSeconds(1800));
        verify(requests).refreshVersion(id, user, current.id());
    }

    @Test
    void missingTranslationFallsBackToTheOriginal() {
        when(versions.translation(current.id(), SupportedLocale.EN)).thenReturn(Optional.empty());
        assertThat(service.query(id.value(), "en").text())
                .isEqualTo(new TermsText(SupportedLocale.KO, "약관", "원문"));
    }

    @Test
    void absentKoreanAndUnknownLocalesReturnTheOriginalWithoutLookingUpTranslations() {
        for (var locale : Arrays.asList(null, "", "ko", "EN", "en-US", " en", "ja"))
            assertThat(service.query(id.value(), locale).text())
                    .isEqualTo(new TermsText(SupportedLocale.KO, "약관", "원문"));
        verify(versions, never()).translation(any(), any());
    }

    @Test
    void translationStoreFailureFailsClosedBeforeRefreshingTheRequest() {
        when(versions.translation(current.id(), SupportedLocale.EN))
                .thenThrow(
                        new PortFailure(
                                PortFailure.Kind.UNAVAILABLE,
                                PortFailure.Execution.UNKNOWN,
                                false));
        assertThatThrownBy(() -> service.query(id.value(), "en"))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(AuthFailure.Reason.LOGIN_UNAVAILABLE));
        verify(requests, never()).refreshVersion(any(), any(), any());
    }
}
