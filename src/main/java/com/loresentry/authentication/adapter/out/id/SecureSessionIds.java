package com.loresentry.authentication.adapter.out.id;

import com.loresentry.authentication.application.port.out.SessionIdGenerator;
import com.loresentry.authentication.domain.SessionId;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

public final class SecureSessionIds implements SessionIdGenerator {
    private final SecureRandom random;

    public SecureSessionIds() {
        this(new SecureRandom());
    }

    public SecureSessionIds(SecureRandom random) {
        this.random = Objects.requireNonNull(random);
    }

    @Override
    public SessionId generate() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return new SessionId(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }
}
