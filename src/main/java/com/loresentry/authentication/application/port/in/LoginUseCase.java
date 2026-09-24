package com.loresentry.authentication.application.port.in;

import java.net.URI;
import java.time.Instant;

/** 외부 로그인 준비와 콜백 처리를 제공하는 애플리케이션 진입점이다. */
public interface LoginUseCase {
    /**
     * 5분간 유효한 로그인 요청을 저장하고 공급자 인증 URL을 반환한다.
     *
     * @return 인증 URL, 콜백과 연결할 요청 식별자 및 만료 시각
     * @throws AuthFailure 요청을 저장하거나 인증 URL을 준비할 수 없는 경우
     */
    PreparedLogin prepare();

    /**
     * 로그인 요청을 검증·소모하고, 공급자 신원 확인과 계정 처리를 거쳐 토큰을 발급한다.
     *
     * <p>요청 소모 이후 실패해도 같은 요청으로 다시 로그인할 수 없다. 실패 시 요청 소모 여부는 {@link AuthFailure#consumption()}으로
     * 확인한다. 토큰은 계정 처리와 RT 저장이 성공한 뒤 반환한다.
     *
     * @param command 준비 단계의 요청 식별자와 공급자가 반환한 state, code 또는 error
     * @return AT·RT와 로그인 요청의 소모 결과
     * @throws AuthFailure 요청이 유효하지 않거나 로그인이 거부되거나 후속 처리가 실패한 경우
     */
    LoginResult callback(Callback command);

    /**
     * BFF가 브라우저의 인증 이동과 콜백 연결에 사용할 준비 결과다.
     *
     * @param authorizationUrl 공급자 로그인 페이지로 이동할 URL
     * @param loginRequestId BFF가 콜백 처리 시 다시 전달할 요청 식별자
     * @param expiresAt 로그인 요청의 만료 시각
     */
    record PreparedLogin(URI authorizationUrl, String loginRequestId, Instant expiresAt) {
        @Override
        public String toString() {
            return "PreparedLogin[REDACTED]";
        }
    }

    /**
     * BFF가 전달한 콜백 입력이며, 이 객체 생성만으로 검증되지는 않는다.
     *
     * @param loginRequestId 준비 단계에서 발급한 요청 식별자
     * @param state 공급자가 돌려준 요청 검증 값
     * @param code 인증 코드. code와 error 중 정확히 하나만 비어 있지 않아야 함
     * @param error 공급자의 오류 코드. 정상 콜백에서는 null 또는 공백
     */
    record Callback(String loginRequestId, String state, String code, String error) {
        @Override
        public String toString() {
            return "Callback[REDACTED]";
        }
    }

    /**
     * 로그인 성공 시 반환하는 토큰과 요청 소모 결과다.
     *
     * @param tokens 발급한 AT·RT
     * @param consumption 성공 시 {@link AuthFailure.Consumption#CONSUMED}
     */
    record LoginResult(TokenPair tokens, AuthFailure.Consumption consumption) {}
}
