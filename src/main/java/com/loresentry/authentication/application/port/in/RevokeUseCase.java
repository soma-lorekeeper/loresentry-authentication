package com.loresentry.authentication.application.port.in;

public interface RevokeUseCase {
    void revoke(String refreshToken);
}
