package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;

import com.loresentry.authentication.application.port.in.*;
import com.loresentry.authentication.application.port.out.*;
import com.loresentry.authentication.support.DatabaseTestSupport;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class AccountProfileTest extends DatabaseTestSupport {
    @Autowired RegisterIdentityUseCase registration;
    @Autowired AccountUseCase accounts;

    @Test
    void reloginKeepsEditedNameAndOnlyUpdatesAvailableEmail() {
        var subject = UUID.randomUUID().toString();
        var first = registration.register(new OidcClient.Identity("google", subject, "  ", null));
        assertThat(accounts.get(first.id()).displayName()).isEqualTo("사용자");
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
                        new AccountUseCase.Profile(first.id(), "chosen", "updated@example.test"));
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
}
