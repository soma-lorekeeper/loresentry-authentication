package com.loresentry.authentication;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.loresentry.authentication.adapter.out.id.SecureSessionIds;
import com.loresentry.authentication.domain.SessionId;
import java.security.SecureRandom;
import java.util.List;
import org.junit.jupiter.api.Test;

class SessionIdTest {
    @Test
    void usesExactly32RandomBytesAndHashesTheCanonicalAsciiCredential() {
        var random = mock(SecureRandom.class);
        doAnswer(
                        call -> {
                            byte[] bytes = call.getArgument(0);
                            assertThat(bytes).hasSize(32);
                            java.util.Arrays.fill(bytes, (byte) 255);
                            return null;
                        })
                .when(random)
                .nextBytes(any(byte[].class));
        var id = new SecureSessionIds(random).generate();
        assertThat(id.value()).isEqualTo("_".repeat(42) + "8");
        assertThat(id.hash())
                .isEqualTo("225f7e75329dd45aa354975d73987319309393af3a4c6733bc13601a4f1b8796");
        assertThat(id.toString()).doesNotContain(id.value()).contains("redacted");
        verify(random).nextBytes(any(byte[].class));
    }

    @Test
    void rejectsPaddingNoncanonicalTrailingBitsAndNonCredentialsWithoutEchoingInput() {
        for (String value :
                List.of(
                        "",
                        "A".repeat(42),
                        "A".repeat(44),
                        "A".repeat(42) + "B",
                        "A".repeat(43) + "=",
                        "/".repeat(43),
                        "f".repeat(64),
                        "00000000-0000-4000-8000-000000000000")) {
            assertThatThrownBy(() -> new SessionId(value))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Invalid session ID");
        }
        assertThatThrownBy(() -> new SessionId(null)).hasMessage("Invalid session ID");
    }
}
