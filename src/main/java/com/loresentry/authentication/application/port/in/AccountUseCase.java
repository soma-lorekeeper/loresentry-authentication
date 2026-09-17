package com.loresentry.authentication.application.port.in;

import java.util.UUID;

public interface AccountUseCase {
    Profile get(UUID userId);
    Profile rename(UUID userId, String displayName);

    record Profile(UUID id, String displayName, String email) {}
}
