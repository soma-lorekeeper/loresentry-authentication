package com.loresentry.authentication.application.port.out;

import java.time.Instant;
import java.util.Optional;

/** 로그인 요청의 임시 상태를 저장하고, 콜백에서 한 번만 소모하도록 제공한다. */
public interface OAuthStateStore {
    /**
     * 요청 생성 당시의 설정과 콜백 검증에 필요한 값이다.
     *
     * <p>nonce와 codeVerifier 원문은 서버에서 보관하며 브라우저 응답에 그대로 노출하지 않는다.
     *
     * @param schemaVersion 저장 데이터의 스키마 버전
     * @param registrationId 공급자 등록 식별자
     * @param clientId 요청 생성 당시의 OAuth 클라이언트 식별자
     * @param redirectUri 요청 생성 당시의 콜백 주소
     * @param state 콜백과 준비 요청의 연결을 확인하는 값
     * @param nonce ID 토큰과 인증 요청의 연결을 확인하는 원문 값
     * @param codeVerifier PKCE 코드 교환에 사용할 원문 검증 값
     * @param createdAt 요청 생성 시각
     * @param expiresAt 요청 만료 시각
     */
    record State(
            int schemaVersion,
            String registrationId,
            String clientId,
            String redirectUri,
            String state,
            String nonce,
            String codeVerifier,
            Instant createdAt,
            Instant expiresAt) {
        @Override
        public String toString() {
            return "OAuthState[REDACTED]";
        }
    }

    /**
     * 동일한 요청 식별자가 없을 때만 임시 상태를 저장한다.
     *
     * @param loginRequestId 요청마다 생성한 식별자
     * @param state 저장할 로그인 요청 상태
     * @return 저장하면 true, 식별자 충돌이 확인되면 false
     * @throws PortFailure 저장 실패 또는 결과를 확인할 수 없는 경우
     */
    boolean create(String loginRequestId, State state);

    /**
     * 요청을 삭제하지 않고 저장된 상태를 조회한다.
     *
     * @param loginRequestId 조회할 요청 식별자
     * @return 저장된 상태. 키가 없으면 빈 Optional
     * @throws PortFailure 저장소 조회나 데이터 해석에 실패한 경우
     */
    Optional<State> find(String loginRequestId);

    /**
     * 요청의 조회와 삭제를 원자적으로 수행한다.
     *
     * <p>성공한 요청은 다시 소모할 수 없다. 조회 후 데이터 해석이 실패해도 삭제는 이미 실행됐을 수 있다. 결과가 불명확한 경우 재호출만으로 최초 실행 결과를 판별할
     * 수 없다.
     *
     * @param loginRequestId 소모할 요청 식별자
     * @return 삭제한 상태. 키가 없으면 빈 Optional
     * @throws PortFailure 저장소 호출이나 데이터 해석에 실패한 경우. execution에 실행 여부를 포함
     */
    Optional<State> consume(String loginRequestId);
}
