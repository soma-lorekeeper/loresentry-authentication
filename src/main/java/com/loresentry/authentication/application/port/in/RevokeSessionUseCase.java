package com.loresentry.authentication.application.port.in;

public interface RevokeSessionUseCase {
    void revoke(String sessionId);
}
