package com.loresentry.authentication.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/** OAuth 요청의 난수 생성, 형식 확인과 nonce·PKCE용 해시 변환을 제공한다. */
public final class OAuthSecrets {
    private OAuthSecrets() {}

    /**
     * 32바이트 난수를 패딩 없는 Base64url 문자열로 만든다.
     *
     * @param random 암호학적 난수 생성기
     * @return 43자 길이의 난수 문자열
     */
    public static String generate(SecureRandom random) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * ASCII 원문의 SHA-256 해시를 패딩 없는 Base64url로 인코딩한다.
     *
     * @param value 이 클래스가 생성한 nonce 또는 PKCE 검증 값
     * @return Google 요청에 넣을 nonce 해시 또는 S256 code_challenge
     */
    public static String hash(String value) {
        try {
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /**
     * 입력이 이 서비스에서 사용하는 난수 문자열 형식인지 확인한다.
     *
     * @param value 검사할 값. null을 허용
     * @return Base64url 문자로 구성된 43자 문자열이면 true. 발급·저장 여부는 검사하지 않음
     */
    public static boolean valid(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{43}");
    }
}
