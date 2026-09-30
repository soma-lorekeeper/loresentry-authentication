# Auth 로그인 흐름

> **책임:** OAuth·계정·동의·세션을 연결하는 로그인 처리 순서와 계정 커밋 후 실패 복구를 정한다.
>
> **확인할 때:** 로그인 전체 흐름이나 단계 사이의 호출·실패 처리를 구현할 때.
>
> **관련 기준:** Google 검증은 [OAuth 상태](OAUTH_STATE.md), 미동의 분기는 [동의 설계](../account/TERMS_CONSENT_DESIGN.md)를 본다.

Auth는 [BFF 로그인 경로](../../../loresentry-gateway/docs/auth/LOGIN_FLOW.md)의 내부 요청을 처리한다.

아래는 현재 구현 흐름이다. 동의 검사의 활성화 조건과 세부 처리는 [약관 동의 설계](../account/TERMS_CONSENT_DESIGN.md#2-google-인증-이후의-동의-흐름)에서 관리한다.

## 처리 흐름

1. OAuth 임시 상태를 저장하고 Google URL·브라우저 연결용 식별자·만료 시각을 반환한다.
2. 콜백의 임시 식별자와 state를 검증하고 상태를 한 번 소비한다.
3. Google 코드를 교환해 ID Token을 검증하고 [계정 규칙](../account/AUTH_ERD.md#2-계정-저장-규칙)에 따라 사용자를 조회·저장한다.
4. 계정 커밋 후 활성화된 동의 검사를 수행한다. 최신 시행 버전에 미동의한 계정은
   `TERMS_REQUIRED`와 동의 대기를 반환하고 세션을 생성하지 않는다.
5. 동의한 계정 또는 동의 검사가 비활성화된 경우에는 안전한 난수 세션 ID를 만들고
   공유 저장소의 활성 세션을 교체한다.
6. 저장 성공 후 `AUTHENTICATED`와 세션 정보·임시 요청 소비 상태를 BFF에 반환한다.
   별도 동의 완료 API는 동의 기록 커밋 후 같은 세션 발급 처리를 사용한다.

## 계정 생성 후 세션 저장 실패

DB 커밋 후 세션 저장이 실패하거나 결과를 확인하지 못해도 계정은 유지한다.
ID를 반환하지 않고 `LOGIN_UNAVAILABLE`을 응답한다. 복구 후 같은 소셜 계정으로
다시 로그인하면 기존 사용자 UUID를 사용한다. 생성 단계 내부 결함은 `INTERNAL_ERROR`다.
저장 결과가 불명확하면 이전 세션 유지도 보장하지 않는다. 자동 재전송·이전 세션 복원은 하지 않는다.

동시 로그인은 Redis에 마지막으로 적용된 활성 세션 하나만 유효하다.
DB 트랜잭션 자체 실패는 [영속성 규칙](../account/AUTH_ERD.md#트랜잭션-경계)에 따라 롤백한다.

## 상세 계약

- OAuth 검증과 일회성 소비: [OAuth 상태](OAUTH_STATE.md).
- 세션 생성·해시·만료·단일 로그인: [세션 계약](../session/SESSION_DESIGN.md).
- 요청·응답·오류: [Auth 제공 API](../API.md).
- 임시 쿠키 정리: [BFF 로그인](../../../loresentry-gateway/docs/auth/LOGIN_FLOW.md#oauth-임시-쿠키).
