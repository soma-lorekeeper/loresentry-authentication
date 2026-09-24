package com.loresentry.authentication.adapter.out.jwt;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.*;
import java.util.Base64;
import java.util.UUID;

public record JwtKeys(RSAPrivateKey privateKey, RSAPublicKey publicKey, String kid) {
    public static JwtKeys load(String privateBase64, String publicPath, String kid) {
        try {
            var id = UUID.fromString(kid);
            if (id.version() != 4 || !id.toString().equalsIgnoreCase(kid))
                throw new IllegalArgumentException();
            if (privateBase64 == null || !privateBase64.matches("[A-Za-z0-9+/]+={0,2}"))
                throw new IllegalArgumentException();
            var factory = KeyFactory.getInstance("RSA");
            var privateKey =
                    (RSAPrivateKey)
                            factory.generatePrivate(
                                    new PKCS8EncodedKeySpec(
                                            Base64.getDecoder().decode(privateBase64)));
            var pem = Files.readString(Path.of(publicPath), StandardCharsets.US_ASCII).strip();
            if (!pem.startsWith("-----BEGIN PUBLIC KEY-----")
                    || !pem.endsWith("-----END PUBLIC KEY-----"))
                throw new IllegalArgumentException();
            var encoded =
                    pem.substring(
                                    "-----BEGIN PUBLIC KEY-----".length(),
                                    pem.length() - "-----END PUBLIC KEY-----".length())
                            .replaceAll("\\s", "");
            var publicKey =
                    (RSAPublicKey)
                            factory.generatePublic(
                                    new X509EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (privateKey.getModulus().bitLength() < 2048
                    || publicKey.getModulus().bitLength() < 2048)
                throw new IllegalArgumentException();
            var challenge = "loresentry-key-pair-check".getBytes(StandardCharsets.US_ASCII);
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(challenge);
            var signed = signature.sign();
            signature.initVerify(publicKey);
            signature.update(challenge);
            if (!signature.verify(signed)) throw new IllegalArgumentException();
            return new JwtKeys(privateKey, publicKey, kid);
        } catch (Exception failure) {
            throw new IllegalStateException("Invalid JWT key configuration");
        }
    }

    @Override
    public String toString() {
        return "JwtKeys[REDACTED]";
    }
}
