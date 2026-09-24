package com.loresentry.authentication.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

/** Runtime-generated fixtures live only in the test process and a temporary public-key file. */
public final class TestKeys {
    public static final KeyPair PAIR = generate(2048);
    public static final String KID = UUID.randomUUID().toString();
    public static final String PRIVATE =
            Base64.getEncoder().encodeToString(PAIR.getPrivate().getEncoded());
    public static final Path PUBLIC_FILE = publicFile(PAIR);

    private TestKeys() {}

    public static KeyPair generate(int bits) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(bits);
            return generator.generateKeyPair();
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    public static Path publicFile(KeyPair pair) {
        try {
            var file = Files.createTempFile("auth-test-public-", ".pem");
            Files.writeString(
                    file,
                    "-----BEGIN PUBLIC KEY-----\n"
                            + Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())
                            + "\n-----END PUBLIC KEY-----\n");
            file.toFile().deleteOnExit();
            return file;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
