package com.loresentry.authentication.application.port.out;

public interface PasswordHasher {
    String hash(String password);

    boolean matches(String password, String hash);
}
