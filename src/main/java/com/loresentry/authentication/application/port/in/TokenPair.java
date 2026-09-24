package com.loresentry.authentication.application.port.in;

import java.time.Instant;

/**
 * BFF에 전달할 AT·RT 문자열과 각각의 만료 시각이다.
 *
 * <p>쿠키 설정은 BFF가 담당한다. 토큰 노출을 줄이기 위해 문자열 표현에서는 값을 숨긴다.
 *
 * @param accessToken 서비스 API 인증에 사용할 AT
 * @param accessExpiresAt AT의 만료 시각
 * @param refreshToken AT·RT 갱신에 사용할 RT
 * @param refreshExpiresAt RT의 만료 시각
 */
public record TokenPair(
        String accessToken,
        Instant accessExpiresAt,
        String refreshToken,
        Instant refreshExpiresAt) {
    @Override
    public String toString() {
        return "TokenPair[REDACTED]";
    }
}
