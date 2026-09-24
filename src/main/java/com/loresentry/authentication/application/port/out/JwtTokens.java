package com.loresentry.authentication.application.port.out;

import com.loresentry.authentication.application.port.in.TokenPair;
import java.time.Instant;
import java.util.UUID;

/** 서비스 토큰 발급과 RT 자체의 검증을 담당하며, 저장소의 사용 권한은 별도로 확인한다. */
public interface JwtTokens {
    /**
     * 발급한 토큰 쌍과 RT 저장소에 등록할 jti를 묶는다.
     *
     * @param tokens 발급한 AT·RT
     * @param refreshJti RT를 저장·소모·폐기할 때 사용할 식별자
     */
    record Issued(TokenPair tokens, UUID refreshJti) {}

    /**
     * 검증된 RT의 사용자, 토큰 식별자와 만료 시각이다.
     *
     * @param userId RT의 subject에서 확인한 사용자 식별자
     * @param sid 로그인 세션의 UUID v4 식별자
     * @param jti RT의 고유 식별자
     * @param expiresAt RT 클레임의 만료 시각
     */
    record RefreshClaims(UUID userId, UUID sid, UUID jti, Instant expiresAt) {}

    /**
     * 사용자의 새 AT·RT를 생성한다.
     *
     * @param userId null이 아닌 서비스 사용자 식별자
     * @param sid 로그인 동안 유지할 UUID v4 세션 식별자
     * @return 토큰 쌍과 RT의 jti. RT 저장소 등록은 호출자가 수행해야 함
     * @throws PortFailure 토큰 서명에 실패한 경우
     */
    Issued issue(UUID userId, UUID sid);

    /**
     * RT의 서명과 클레임을 검증한다.
     *
     * <p>성공해도 RT가 저장소에 남아 있거나 아직 소모되지 않았음을 보장하지 않는다.
     *
     * @param token 검증할 RT 문자열
     * @param allowExpired 폐기 용도로 만료 RT를 허용할 때만 true. 다른 검증은 생략하지 않음
     * @return 검증된 RT 클레임
     * @throws PortFailure 토큰 형식·서명·클레임 검증에 실패한 경우
     */
    RefreshClaims verifyRefresh(String token, boolean allowExpired);
}
