# Auth 내부 API

**BFF가 호출하는 Auth의 로그인·토큰·본인 계정 API 계약이다.**
2026-09-24 단일 세션 Auth 구현에 맞춘 계약이다. BFF 연동·운영 배포는 후속 작업이다.
외부 브라우저 API 경로와 쿠키 속성은 이 문서에서 정하지 않는다.

## 공통 규칙

- 기본 경로는 `/auth`이며, 본문이 있는 요청·응답은 JSON을 사용한다.
- 호출 신뢰는 [내부 서비스 호출 계약](../../docs/bff/INTERNAL_SERVICE_CALLS.md#내부-서비스-호출)을 따른다.
- 본인 계정 API는 [사용자 정보 전달 계약](../../docs/bff/INTERNAL_SERVICE_CALLS.md#사용자-정보-전달)의
  `X-User-Id`를 사용한다. 로그인·재발급·폐기 API에는 이 헤더를 필수로 요구하지 않는다.
- UUID는 문자열, 시각은 UTC의 ISO 8601 문자열로 표현한다. 토큰 응답에는 `Cache-Control: no-store`를 적용한다.
- 토큰·OAuth 코드·임시 식별자는 내부 호출의 URL이나 로그에 기록하지 않는다.

## API 목록

아래 경로는 기본 경로 뒤에 붙는다.

| 기능 | 메서드·경로 | 입력 | 성공 응답 |
|---|---|---|---|
| Google 로그인 준비 | `POST /oauth/google/prepare` | 빈 JSON 객체 | `200`, 로그인 준비 결과 |
| Google 콜백 처리 | `POST /oauth/google/callback` | 콜백 정보 | `200`, 토큰 쌍 |
| 토큰 재발급 | `POST /tokens/refresh` | `refresh_token` | `200`, 토큰 쌍 |
| 해당 세션 폐기 | `POST /tokens/revoke` | `refresh_token` | `204`, 본문 없음 |
| 본인 계정 조회 | `GET /users/me` | `X-User-Id` | `200`, 계정 정보 |
| 본인 표시 이름 수정 | `PATCH /users/me` | `X-User-Id`, `display_name` | `200`, 수정된 계정 정보 |

## 로그인

준비 응답은 다음 필드를 반환한다.

| 필드 | 의미 |
|---|---|
| `authorization_url` | Auth가 생성한 Google 로그인 URL |
| `login_request_id` | BFF가 브라우저 연결용 임시 쿠키에 담을 불투명한 식별자 |
| `expires_at` | OAuth 임시 상태의 만료 시각 |

콜백 요청은 `login_request_id`, `state`와 함께 성공 시 `code`, 제공자 오류 시 `error`를 전달한다.
`code`와 `error`는 동시에 허용하지 않는다. 임시 상태와 브라우저 연결을 검증한 뒤
[로그인 흐름](login/LOGIN_FLOW.md)에 따라 처리한다. 제공자의 오류 설명을 응답이나 로그에 그대로 노출하지 않는다.
Google에 등록한 리다이렉트 URI는 서버 설정을 사용하며, 호출자가 임의로 지정하지 않는다.

콜백의 성공·오류 응답에는 `login_request_consumed`를 추가한다.
검증된 임시 상태의 `GETDEL` 소비를 확인했으면 `true`, 미소비가 확인되면 `false`,
타임아웃 등으로 확인하지 못했으면 `null`이다. 로그인 준비·재발급 등 다른 API에는 이 필드를 넣지 않는다.
BFF는 이 결과를 [임시 쿠키 정리](../../loresentry-gateway/docs/auth/LOGIN_FLOW.md#oauth-임시-쿠키)에 사용한다.
임시 상태 생성·직렬화와 Google 요청 파라미터는 [OAuth 상태 계약](login/OAUTH_STATE.md)을 따른다.

## 토큰 응답

로그인 성공과 재발급 성공은 다음 필드를 공통으로 반환한다.

| 필드 | 의미 |
|---|---|
| `access_token` | 발급한 AT 원문 |
| `access_expires_at` | AT 만료 시각 |
| `refresh_token` | 발급한 RT 원문 |
| `refresh_expires_at` | RT 만료 시각 |

로그인의 세션 교체 또는 재발급의 조건부 갱신까지 성공해야 토큰 쌍을 반환한다. 이 응답은 BFF 내부 연동용이며,
브라우저 전달은 [BFF 인증 쿠키 처리](../../loresentry-gateway/docs/BROWSER_SECURITY.md#브라우저-경계)를 따른다.
최초 로그인에서 계정 생성 후 RT 저장에 실패한 경우는 [로그인 실패 처리](login/LOGIN_FLOW.md#계정-생성-후-토큰-저장-실패)를 따른다.

## 폐기

RT를 검증하고 미만료 RT의 `sid`와 현재 사용자 세션이 같을 때만 삭제한다.
현재 RT `jti` 일치는 요구하지 않는다. 회전 전의 미만료 RT로도 같은 세션을 로그아웃할 수 있다.
삭제 완료·키 부재·다른 sid가 확인되면 `204`를 반환한다.
서명과 필수 필드가 유효하고 이미 만료된 RT도 더 이상 사용할 수 없으므로 `204`로 처리한다.
서명·발급자·사용 대상·토큰 종류 검증을 통과하지 못한 입력은 거절한다.
삭제 재시도는 [로그아웃 폐기 정책](token/REFRESH_TOKEN_DESIGN.md#로그아웃-폐기-실패)을 따른다.

## 본인 계정

응답 필드는 `id`, `display_name`, `email`이다. `email`은 현재 연결된 소셜 로그인 정보의
값이며, 없으면 `null`을 반환한다. 계정 연결을 도입할 때 이메일 선택 규칙을 다시 설계한다.
표시 이름 수정 요청은 `display_name`만 허용하며, 사용자 ID와 이메일 수정은 허용하지 않는다.
입력 검증은 [계정 저장 규칙](account/AUTH_ERD.md#2-초기-저장-규칙)을 따른다.

## 오류 계약

오류 본문은 `code`, `message`, `next_action`으로 구성한다.
호출자는 `message` 대신 `code`를 기준으로 처리한다. `next_action`은 오류 이후의 처리 방향이며,
토큰 폐기나 Redis 명령 실행 여부를 증명하는 값은 아니다.

| HTTP | `code` | 조건 | `next_action` |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | 필수 입력 누락·형식 오류·사용자 헤더 중복 또는 UUID 형식 오류·허용하지 않은 수정 필드 | `NONE` |
| 400 | `INVALID_DISPLAY_NAME` | 표시 이름 검증 실패 | `NONE` |
| 400 | `OAUTH_REQUEST_INVALID` | 임시 상태 만료·부재·소비 또는 브라우저 연결·state 불일치 | `RESTART_LOGIN` |
| 400 | `OAUTH_LOGIN_DENIED` | Google 로그인 취소·거절 | `RESTART_LOGIN` |
| 401 | `OAUTH_IDENTITY_INVALID` | Google ID Token 등 신원 검증 실패 | `RESTART_LOGIN` |
| 401 | `REFRESH_REJECTED` | RT 만료·검증 실패, 세션 부재·만료 또는 sid·jti·exp 불일치 | `RELOGIN` |
| 401 | `INVALID_REFRESH_TOKEN` | 폐기 요청의 RT 검증 실패 | `NONE` |
| 401 | `USER_CONTEXT_REQUIRED` | 본인 계정 API의 사용자 헤더 누락 | `RELOGIN` |
| 404 | `USER_NOT_FOUND` | 본인 계정 API의 사용자 없음 | `RELOGIN` |
| 503 | `LOGIN_UNAVAILABLE` | 로그인 중 DB·Redis·Google 통신 장애 | `RESTART_LOGIN` |
| 503 | `REFRESH_UNAVAILABLE` | 조건부 갱신 명령 미실행이 확인된 저장소 장애 | `RETRY_LATER` |
| 503 | `REFRESH_OUTCOME_UNKNOWN` | 타임아웃 등으로 조건부 갱신 결과를 확인할 수 없음 | `RELOGIN` |
| 503 | `REVOCATION_UNCONFIRMED` | 제한된 삭제 시도 후에도 폐기 완료를 확인하지 못함 | `NONE` |
| 503 | `ACCOUNT_UNAVAILABLE` | 계정 조회·수정 중 DB 장애 | `RETRY_LATER` |
| 500 | `INTERNAL_ERROR` | 서명 실패·손상된 세션 데이터·분류되지 않은 서버 오류 | `NONE` |

동일 RT의 동시 요청 중 조건부 갱신에 실패한 요청도 `REFRESH_REJECTED`로 처리한다.
상태 부재만으로 동시 요청·이전 갱신·폐기를 구분할 수 없으므로 별도 충돌 코드나 복구 토큰을 반환하지 않는다.
재발급의 자동 재시도는 하지 않는다. `RETRY_LATER`는 후속 시도가 가능하다는 뜻이며 즉시 반복 호출 지시가 아니다.
`RELOGIN`도 결과 유실·갱신 여부를 단정하지 않고, 기존에 정한 복구 없는 정책에 따라 재인증으로 진행한다.
저장소·외부 제공자 장애가 남아 있으면 다시 로그인해도 성공하지 못할 수 있다.

폐기 실패 응답은 브라우저 쿠키 삭제 완료를 의미하지 않는다.
쿠키 삭제와 브라우저에 대한 최종 응답은 [BFF 로그아웃 계약](../../loresentry-gateway/docs/auth/LOGOUT_FLOW.md)을 따른다.
