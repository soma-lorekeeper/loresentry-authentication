# Auth 내부 API

**단일 세션 ID 방식으로 전환할 목표 API 계약이다.** 2026-09-26 문서 기준이며 코드에는
아직 반영하지 않았다. 브라우저 API와 쿠키는 BFF가 관리한다.

## 공통 규칙

- 기본 경로는 `/auth`, 본문은 JSON, 필드 이름은 snake case다.
- UUID는 문자열, HTTP 시각은 UTC ISO 8601이다. 인증·계정 응답에는 `Cache-Control: no-store`를 적용한다.
- [내부 호출 신뢰](../../docs/bff/INTERNAL_SERVICE_CALLS.md)를 전제로 한다.
- 계정 API만 검증된 `X-User-Id`를 요구한다. 로그인·폐기는 각각의 입력으로 처리한다.
- 세션 ID·OAuth 코드·임시 상태는 URL·로그·예외 원문에 노출하지 않는다.

## API 목록

| 기능 | 메서드·경로 | 입력 | 성공 |
|---|---|---|---|
| Google 로그인 준비 | `POST /oauth/google/prepare` | 빈 JSON 객체 | `200`, 준비 결과 |
| Google 콜백 | `POST /oauth/google/callback` | 콜백 정보 | `200`, 새 세션 정보 |
| 세션 폐기 | `POST /sessions/revoke` | `session_id` | `204`, 본문 없음 |
| 본인 계정 조회 | `GET /users/me` | `X-User-Id` | `200`, 계정 |
| 표시 이름 수정 | `PATCH /users/me` | `X-User-Id`, `display_name` | `200`, 수정된 계정 |

활동 시 연장은 BFF의 공유 저장소 연산이며 별도 Auth HTTP API를 두지 않는다.

## 로그인

준비 결과는 `authorization_url`, `login_request_id`, `expires_at`이다.
콜백 입력은 `login_request_id`, `state`와 성공의 `code` 또는 제공자 오류의 `error`다.
`code`와 `error`는 동시에 허용하지 않는다. 제공자 오류 설명은 그대로 노출하지 않는다.
Google 콜백 URI는 서버 설정을 사용하며 호출자가 지정하지 않는다.

성공 콜백은 다음 필드를 반환한다. 세션 교체 성공을 확인하기 전에는 반환하지 않는다.

| 필드 | 의미 |
|---|---|
| `session_id` | CSPRNG 32바이트의 padding 없는 Base64URL. BFF 쿠키용 인증 비밀값 |
| `expires_at` | 로그인 시 설정한 비활동 만료 시각. 이후 인증 활동으로 연장됨 |
| `login_request_consumed` | OAuth 임시 상태의 소비 결과 |

콜백 성공·오류 응답의 `login_request_consumed`는 소비 확인 `true`, 미소비 확인 `false`,
결과 불명 `null`이다. 다른 API에는 포함하지 않는다. BFF는 이를
[임시 쿠키 정리](../../loresentry-gateway/docs/auth/LOGIN_FLOW.md#oauth-임시-쿠키)에 사용한다.
DB 커밋 후 실패는 [계정 유지 정책](login/LOGIN_FLOW.md#계정-생성-후-세션-저장-실패)을 따른다.

## 폐기

`session_id` 원문을 JSON 본문으로 받는다. UUID나 해시를 대체 입력으로 받지 않는다.
[세션 계약](session/SESSION_DESIGN.md#로그아웃)에 따라 ID를 해시하고 해당 세션만 폐기한다.
부재·만료·이미 교체됨·이미 폐기됨은 `204`다. 이전 ID가 현재의 새 로그인을 지우지 않는다.
형식 오류는 `400 INVALID_SESSION_ID`, 저장소 오류·결과 불명은
`503 REVOCATION_UNCONFIRMED`다. 허용된 제한적 재시도도 같은 ID 조건을 유지한다.

## 본인 계정

응답은 `id`, `display_name`, `email`이며 이메일이 없으면 `null`이다.
수정은 `display_name`만 허용한다. [계정 규칙](account/AUTH_ERD.md#2-초기-저장-규칙)을 유지한다.

## 오류 계약

본문은 `code`, `message`, `next_action`이다. 호출자는 code로 분기한다.
next_action은 안내이며 세션 상태 변경이나 명령 실행 여부의 증거가 아니다.

| HTTP | code | 조건 | next_action |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | JSON·필수 입력·헤더·수정 필드 오류 | `NONE` |
| 400 | `INVALID_DISPLAY_NAME` | 표시 이름 검증 실패 | `NONE` |
| 400 | `INVALID_SESSION_ID` | 폐기 ID 누락·형식 오류 | `NONE` |
| 400 | `OAUTH_REQUEST_INVALID` | 임시 상태·브라우저 연결·state 검증 실패 | `RESTART_LOGIN` |
| 400 | `OAUTH_LOGIN_DENIED` | Google 로그인 취소·거절 | `RESTART_LOGIN` |
| 401 | `OAUTH_IDENTITY_INVALID` | Google 신원 검증 실패 | `RESTART_LOGIN` |
| 401 | `USER_CONTEXT_REQUIRED` | 본인 계정 사용자 헤더 누락 | `RELOGIN` |
| 404 | `USER_NOT_FOUND` | 계정 없음 | `RELOGIN` |
| 503 | `LOGIN_UNAVAILABLE` | DB·Redis·Google 장애 또는 세션 생성 결과 불명 | `RESTART_LOGIN` |
| 503 | `REVOCATION_UNCONFIRMED` | 세션 폐기 완료 미확인 | `NONE` |
| 503 | `ACCOUNT_UNAVAILABLE` | 계정 저장소 장애 | `RETRY_LATER` |
| 500 | `INTERNAL_ERROR` | 로그인 생성 단계의 손상 레코드·내부 결함 등 미분류 오류 | `NONE` |

BFF의 보호 요청 검증·연장 실패는 [외부 API](../../loresentry-gateway/docs/EXTERNAL_API.md#보호-api-인증-실패)의
세션 오류다. 폐기 실패는 브라우저 쿠키 처리와 별개이며
[BFF 로그아웃](../../loresentry-gateway/docs/auth/LOGOUT_FLOW.md)을 따른다.
