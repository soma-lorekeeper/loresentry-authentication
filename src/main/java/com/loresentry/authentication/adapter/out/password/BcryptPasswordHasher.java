package com.loresentry.authentication.adapter.out.password;

import com.loresentry.authentication.application.port.out.PasswordHasher;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BcryptPasswordHasher implements PasswordHasher {
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(12);

    @Override
    public String hash(String password) {
        return encoder.encode(password);
    }

    @Override
    public boolean matches(String password, String hash) {
        return hash != null && encoder.matches(password, hash);
    }
}
