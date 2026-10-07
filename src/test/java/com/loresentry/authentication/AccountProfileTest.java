package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.domain.SupportedLocale;
import com.loresentry.authentication.support.DatabaseTestSupport;
import java.sql.Timestamp;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AccountProfileTest extends DatabaseTestSupport {
    @Autowired RegisterIdentityUseCase registration;
    @Autowired AccountUseCase accounts;
    @Autowired AccountStore store;
    @Autowired JdbcTemplate jdbc;

    @Test
    void reloginKeepsEditedNameAndOnlyUpdatesAvailableEmail() {
        var subject = UUID.randomUUID().toString();
        var first = registration.register(new OidcClient.Identity("google", subject, "  ", null));
        assertThat(accounts.get(first.id()).displayName()).isEqualTo("Writer");
        assertThat(accounts.get(first.id()).email()).isNull();
        accounts.rename(first.id(), "  chosen  ");
        var next =
                registration.register(
                        new OidcClient.Identity(
                                "google", subject, "Google changed", "updated@example.test"));
        assertThat(next.id()).isEqualTo(first.id());
        assertThat(next.createdAt()).isEqualTo(first.createdAt());
        assertThat(accounts.get(first.id()))
                .isEqualTo(
                        new AccountUseCase.Profile(
                                first.id(), "chosen", "updated@example.test", false, null));
        registration.register(new OidcClient.Identity("google", subject, null, "  "));
        assertThat(accounts.get(first.id()).email()).isEqualTo("updated@example.test");
        var other =
                registration.register(
                        new OidcClient.Identity(
                                "google",
                                UUID.randomUUID().toString(),
                                "chosen",
                                "updated@example.test"));
        assertThat(other.id()).isNotEqualTo(first.id());
        assertThat(accounts.get(other.id()).displayName()).isEqualTo("chosen");
    }

    @Test
    void namelessSignUpUsesTheEmailLocalPart() {
        var user =
                registration.register(
                        new OidcClient.Identity(
                                "google",
                                UUID.randomUUID().toString(),
                                null,
                                " pen.name@example.test"));
        assertThat(accounts.get(user.id()).displayName()).isEqualTo("pen.name");
        store.delete(user.id());
    }

    @Test
    void localeStartsUnsetPersistsAcrossReloginAndOnlyChangesUpdateTheTime() {
        var subject = UUID.randomUUID().toString();
        var user =
                registration.register(
                        new OidcClient.Identity("google", subject, "Name", "a@example.test"));
        assertThat(accounts.get(user.id()).locale()).isNull();
        assertThat(localeColumn(user.id())).isNull();
        assertThat(accounts.changeLocale(user.id(), "en").locale()).isEqualTo(SupportedLocale.EN);
        assertThat(localeColumn(user.id())).isEqualTo("en");
        var changed = updatedAt(user.id());
        assertThat(changed.toInstant()).isAfter(user.updatedAt());
        assertThat(accounts.changeLocale(user.id(), "en").locale()).isEqualTo(SupportedLocale.EN);
        assertThat(updatedAt(user.id())).isEqualTo(changed);
        registration.register(new OidcClient.Identity("google", subject, "Name", "a@example.test"));
        assertThat(accounts.get(user.id()))
                .isEqualTo(
                        new AccountUseCase.Profile(
                                user.id(), "Name", "a@example.test", false, SupportedLocale.EN));
        assertThat(accounts.changeLocale(user.id(), "ko").locale()).isEqualTo(SupportedLocale.KO);
        assertThat(localeColumn(user.id())).isEqualTo("ko");
        assertThatThrownBy(() -> accounts.changeLocale(user.id(), "fr"))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(AuthFailure.Reason.INVALID_REQUEST));
        assertThat(localeColumn(user.id())).isEqualTo("ko");
        store.delete(user.id());
        assertThatThrownBy(() -> accounts.changeLocale(user.id(), "en"))
                .isInstanceOfSatisfying(
                        AuthFailure.class,
                        failure ->
                                assertThat(failure.reason())
                                        .isEqualTo(AuthFailure.Reason.USER_NOT_FOUND));
    }

    private String localeColumn(UUID user) {
        return jdbc.queryForObject("SELECT locale FROM users WHERE id = ?", String.class, user);
    }

    private Timestamp updatedAt(UUID user) {
        return jdbc.queryForObject(
                "SELECT updated_at FROM users WHERE id = ?", Timestamp.class, user);
    }
}
