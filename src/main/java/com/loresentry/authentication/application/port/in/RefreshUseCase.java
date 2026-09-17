package com.loresentry.authentication.application.port.in;

public interface RefreshUseCase {
    TokenPair refresh(String refreshToken);
}
