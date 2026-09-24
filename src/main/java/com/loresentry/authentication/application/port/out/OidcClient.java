package com.loresentry.authentication.application.port.out;

import java.net.URI;

/** 공급자의 인증 URL 생성과 검증된 신원 조회를 애플리케이션에 제공한다. */
public interface OidcClient {
    /**
     * 로그인 요청을 생성 당시의 공급자 설정과 연결하는 공개 설정 값이다.
     *
     * @param registrationId 애플리케이션 내부의 공급자 등록 식별자
     * @param clientId 공급자에 등록한 OAuth 클라이언트 식별자
     * @param redirectUri 공급자가 인증 결과를 돌려줄 콜백 주소
     */
    record Settings(String registrationId, String clientId, String redirectUri) {}

    /**
     * OIDC 검증을 마친 신원이다. 계정 연결 기준은 이메일이 아닌 provider와 subject다.
     *
     * @param provider 공급자 식별자
     * @param subject 공급자 안에서 사용자를 식별하는 값
     * @param name 공급자의 표시 이름. 없을 수 있음
     * @param email 공급자의 이메일. 없을 수 있음
     */
    record Identity(String provider, String subject, String name, String email) {}

    /**
     * 로그인 요청 생성과 콜백 시 설정 일치 여부 확인에 사용할 값을 반환한다.
     *
     * @return 클라이언트 비밀 값을 포함하지 않는 공급자 설정
     */
    Settings settings();

    /**
     * 준비된 요청 값으로 브라우저가 이동할 인증 URL을 만든다.
     *
     * @param state 공급자 설정, state, nonce 및 PKCE 검증 값을 담은 로그인 요청
     * @return 공급자의 인증 URL. 이 호출 자체가 브라우저를 이동시키지는 않음
     */
    URI authorizationUrl(OAuthStateStore.State state);

    /**
     * 인증 코드를 교환하고 ID 토큰의 서명·클레임·nonce를 검증한 신원을 반환한다.
     *
     * <p>호출자는 로그인 요청의 state와 유효 기간을 확인하고 요청을 소모한 뒤 호출해야 한다.
     *
     * @param code 공급자가 발급한 인증 코드
     * @param state 준비 단계에 저장했던 로그인 요청
     * @return 검증을 통과한 공급자 신원
     * @throws PortFailure 코드 교환이나 신원 검증에 실패한 경우
     */
    Identity exchange(String code, OAuthStateStore.State state);
}
