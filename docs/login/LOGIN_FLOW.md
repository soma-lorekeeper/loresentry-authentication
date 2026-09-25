# Auth 로그인 흐름

2026-09-26 목표 설계다. Auth는 [BFF 로그인 경로](../../../loresentry-gateway/docs/auth/LOGIN_FLOW.md)의 내부 요청을 처리한다.

## 처리 흐름

1. OAuth 임시 상태를 저장하고 Google URL·브라우저 연결용 식별자·만료 시각을 반환한다.
2. 콜백의 임시 식별자와 state를 검증하고 상태를 한 번 소비한다.
3. Google 코드를 교환해 ID Token을 검증하고 [계정 규칙](../account/AUTH_ERD.md#2-초기-저장-규칙)에 따라 사용자를 조회·저장한다.
4. DB 커밋 후 안전한 난수 세션 ID를 만들고 공유 저장소에서 사용자별 활성 세션을 교체한다.
5. 저장 성공이 확인되면 `session_id`, `expires_at`, `login_request_consumed`를 BFF에 반환한다.

## 계정 생성 후 세션 저장 실패

DB 커밋 후 세션 저장이 실패하거나 결과를 확인하지 못해도 계정은 유지한다.
ID를 반환하지 않고 `LOGIN_UNAVAILABLE`을 응답한다. 복구 후 같은 소셜 계정으로
다시 로그인하면 기존 사용자 UUID를 사용한다. 생성 단계 내부 결함은 `INTERNAL_ERROR`다.
저장 결과가 불명확하면 이전 세션 유지도 보장하지 않는다. 자동 재전송·이전 세션 복원은 하지 않는다.

동시 로그인은 Redis에 마지막으로 적용된 활성 세션 하나만 유효하다.
DB 트랜잭션 자체 실패는 [영속성 규칙](../account/PERSISTENCE_DESIGN.md#트랜잭션-경계)에 따라 롤백한다.

## 상세 계약

- OAuth 검증과 일회성 소비: [OAuth 상태](OAUTH_STATE.md).
- 세션 생성·해시·만료·단일 로그인: [세션 계약](../session/SESSION_DESIGN.md).
- 요청·응답·오류: [내부 API](../INTERNAL_API.md).
- 임시 쿠키 정리: [BFF 로그인](../../../loresentry-gateway/docs/auth/LOGIN_FLOW.md#oauth-임시-쿠키).
