# Auth 로그인 흐름

Auth는 [BFF 로그인 경로](../../../loresentry-gateway/docs/auth/LOGIN_FLOW.md)에서 전달받은 내부 요청을 처리한다.

## 처리 흐름

1. **준비:** OAuth 임시 상태를 저장하고 Google 로그인 URL·브라우저 연결용 식별자·만료 시각을 반환한다.
2. **콜백:** BFF가 전달한 임시 쿠키의 식별자와 state로 브라우저 연결을 검증하고 상태를 한 번 소비한다.
3. **신원·계정:** Google과 코드를 교환해 ID Token을 검증한 뒤 [계정 규칙](../account/AUTH_ERD.md#2-초기-저장-규칙)에 따라 사용자를 조회·생성·갱신한다.
4. **토큰:** DB 커밋 후 새 UUID v4 `sid`의 AT·RT를 서명하고 사용자 세션 레코드를 원자적으로 교체한다. 저장 성공 후에만 토큰 쌍과 만료 정보를 BFF에 반환한다.

## 계정 생성 후 토큰 저장 실패

**DB 커밋 후 세션 교체가 실패하거나 결과를 확인하지 못해도 계정은 유지한다.**
토큰 쌍을 반환하지 않고 `LOGIN_UNAVAILABLE`을 반환한다. 신규·기존 계정 모두 삭제하지 않는다.
저장소 복구 후 Google 로그인을 다시 시작하면 같은 `(provider, provider_id)`의 사용자 UUID를 사용한다.
서명 실패는 `500 INTERNAL_ERROR`이며 기존 세션을 변경하지 않는다. 교체 명령의 결과가
불명확하면 이전 세션의 유지를 보장할 수 없다. 자동 재전송·이전 세션 복구는 하지 않는다.
동시 로그인은 Redis에 마지막으로 저장된 세션 하나만 활성 상태로 남는다.
DB 트랜잭션 자체의 실패는 [영속성 규칙](../account/PERSISTENCE_DESIGN.md#트랜잭션-경계)에 따라 전체 롤백한다.

## 상세 계약

- 상태 생성·검증·만료·소비는 [OAuth 상태](OAUTH_STATE.md)를 따른다. 상태 부재·만료 시 로그인을 다시 시작한다.
- 토큰 규격은 [JWT 설계](../token/JWT_DESIGN.md), 요청·응답·오류는 [내부 API](../INTERNAL_API.md)를 따른다.
- 임시 쿠키의 속성과 정리 시점은 [BFF 로그인 계약](../../../loresentry-gateway/docs/auth/LOGIN_FLOW.md#oauth-임시-쿠키)을 따른다.
