# Auth 제공 API

> **책임:** Auth가 제공하는 HTTP 요청·응답·입력 검증·오류 계약을 정한다.
>
> **제공자·호출자:** Auth가 제공하고 BFF가 호출한다.
>
> **확인할 때:** 엔드포인트를 구현하거나 BFF와 요청 필드·상태 코드·오류를 맞출 때.
>
> **관련 기준:** 처리 순서는 [로그인 흐름](login/LOGIN_FLOW.md), 저장 연산은 [세션 계약](session/SESSION_DESIGN.md)을 본다.

단일 세션 ID를 사용하는 현재 Auth API 계약이다. 브라우저 API와 쿠키는 BFF가 관리한다.
Auth가 Google을 호출하는 방법은 [호출 API](API_CALLS.md)에서 관리한다.

## 공통 규칙

- 기본 경로는 `/auth`, 본문은 JSON, 필드 이름은 snake case다.
- UUID는 문자열, HTTP 시각은 UTC ISO 8601이다. 인증·계정 응답에는 `Cache-Control: no-store`를 적용한다.
- Auth는 BFF에서만 호출한다. 외부 직접 접근을 차단하고, BFF가 검증한 사용자 식별정보를 신뢰한다.
- 계정 API만 검증된 `X-User-Id`를 요구한다. 로그인·폐기는 각각의 입력으로 처리한다.
- 약관 응답에도 `Cache-Control: no-store`를 적용한다.
- 동의 대기 ID·세션 ID·OAuth 코드·임시 상태는 URL·로그·예외 원문에 노출하지 않는다.

## 입력 검증

필수 값과 콜백의 `code`·`error` 배타 조건을 검사한다. 알 수 없는 입력 필드와
문자열 필드의 비문자열 값은 거절한다. 표시 이름의 업무 규칙은
[계정 문서](account/AUTH_ERD.md#2-계정-저장-규칙)를 따르고 검증 실패는 아래 오류 계약으로 반환한다.
웹 DTO와 변환 구현은 [서버 구조](ARCHITECTURE.md#데이터와-프레임워크-경계)를 따른다.

## API 목록

| 기능 | 메서드·경로 | 입력 | 성공 |
|---|---|---|---|
| Google 로그인 준비 | `POST /oauth/google/prepare` | 빈 JSON 객체 | `200`, 준비 결과 |
| Google 콜백 | `POST /oauth/google/callback` | 콜백 정보 | `200`, 로그인 완료 또는 동의 대기 |
| 약관 조회 | `GET /terms` | `X-Consent-Request-Id`, 선택 `locale` 쿼리 | `200`, 약관 본문과 대기 만료 |
| 동의 완료 | `POST /terms/accept` | `consent_request_id`, `terms_version_id` | `200`, 새 세션 정보 |
| 세션 폐기 | `POST /sessions/revoke` | `session_id` | `204`, 본문 없음 |
| 본인 계정 조회 | `GET /users/me` | `X-User-Id` | `200`, 계정 |
| 표시 이름 수정 | `PATCH /users/me` | `X-User-Id`, `display_name` | `200`, 수정된 계정 |
| 계정 언어 저장 | `PUT /users/me/locale` | `X-User-Id`, `locale` | `200`, 수정된 계정 |
| 온보딩 완료 | `PUT /users/me/onboarding` | `X-User-Id`, 본문 없음 | `204`, 본문 없음 |
| 회원 탈퇴 | `DELETE /users/me` | `X-User-Id` | `204`, 본문 없음 |

활동 시 연장은 BFF의 공유 저장소 연산이며 별도 Auth HTTP API를 두지 않는다.
약관 원문·동의 기록과 대기 저장은 [동의 설계](account/TERMS_CONSENT_DESIGN.md)를 따른다.

## 로그인

준비 결과는 `authorization_url`, `login_request_id`, `expires_at`이다.
콜백 입력은 `login_request_id`, `state`와 성공의 `code` 또는 제공자 오류의 `error`다.
`code`와 `error`는 동시에 허용하지 않는다. 제공자 오류 설명은 그대로 노출하지 않는다.
Google 콜백 URI는 서버 설정을 사용하며 호출자가 지정하지 않는다.

콜백의 `status`는 `AUTHENTICATED` 또는 `TERMS_REQUIRED`다.
로그인 완료는 세션 저장 성공 후 아래 필드를 반환하며 동의 대기 ID는 생략한다.

| 필드 | 의미 |
|---|---|
| `status` | `AUTHENTICATED` |
| `session_id` | CSPRNG 32바이트의 padding 없는 Base64URL. BFF 쿠키용 인증 비밀값 |
| `expires_at` | 로그인 시 설정한 비활동 만료 시각. 이후 인증 활동으로 연장됨 |
| `login_request_consumed` | OAuth 임시 상태의 소비 결과 |

`TERMS_REQUIRED`는 `status`, `consent_request_id`, 대기의 `expires_at`,
`login_request_consumed: true`만 반환한다. 이 분기에서는 세션을 생성하지 않는다.
동의 대기와 로그인 세션 ID를 함께 반환하지 않는다.

콜백 성공·오류 응답의 `login_request_consumed`는 소비 확인 `true`, 미소비 확인 `false`,
결과 불명 `null`이다. 다른 API에는 포함하지 않는다. BFF는 이를
[임시 쿠키 정리](../../loresentry-gateway/docs/auth/LOGIN_FLOW.md#oauth-임시-쿠키)에 사용한다.
DB 커밋 후 실패는 [계정 유지 정책](login/LOGIN_FLOW.md#계정-생성-후-세션-저장-실패)을 따른다.

## 약관 조회와 동의 완료

| 기능 | Auth 경로 | 내부 입력 | 성공 |
|---|---|---|---|
| 약관 조회 | `GET /auth/terms` | `X-Consent-Request-Id` 헤더, 선택 `locale` 쿼리 | `200`, 아래 약관 필드 |
| 동의 완료 | `POST /auth/terms/accept` | JSON의 `consent_request_id`, `terms_version_id` | `200`, `session_id`, `expires_at` |

조회 응답은 `terms_version_id`, `version`, `title`, `content`, `locale`, `effective_at`, `expires_at`이다.
마지막 필드는 동의 대기의 만료다. Auth는 현재 적용 버전을 반환하고 대기의 대상 버전을
그 버전으로 갱신하되 만료는 유지한다. 조회 도중 소비된 대기를 재생성하지 않는다.

`locale` 쿼리가 정확히 `en`이고 반환할 버전에 `en` 번역이 있으면 `title`·`content`는 번역이다.
쿼리 누락·`ko`·그 밖의 값(`EN`, `en-US` 등)이나 번역이 없는 버전은 오류 없이 한국어 원문을 반환한다.
응답의 `locale`은 반환한 `title`·`content`의 언어로 `ko` 또는 `en`이다. 번역은 같은 버전에
연결되며 `terms_version_id`·`version`·`effective_at`은 언어와 관계없이 같다. 번역 조회 실패는
다른 조회 실패와 같이 `503 LOGIN_UNAVAILABLE`이며 대기를 갱신하지 않는다.

```json
{
  "terms_version_id": "b226d203-1e9f-4435-8dc8-7a2dc9fcd505",
  "version": "v0",
  "title": "Lore Sentry Terms of Service",
  "content": "Article 1. Purpose and service\n\n...",
  "locale": "en",
  "effective_at": "2026-10-07T00:00:00Z",
  "expires_at": "2026-10-07T00:30:00Z"
}
```

완료 입력의 버전 ID는 사용자가 확인한 버전이다. 번역을 확인했어도 같은 `terms_version_id`로 동의한다. Auth는 대기에 연결된 버전 및 현재 적용 버전과
모두 일치할 때만 저장한다. 성공 응답은 `session_id`, `expires_at`이며 BFF 내부에서만 사용한다.

### 동의 오류

| HTTP | code | 처리 | next_action |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | 입력·UUID 형식 오류. 동의 저장 없음 | `NONE` |
| 401 | `CONSENT_REQUEST_INVALID` | 대기 부재·만료·소비됨 또는 연결 계정 없음 | `RESTART_LOGIN` |
| 409 | `TERMS_VERSION_MISMATCH` | 제출·대기·현재 버전 불일치. 대기는 소비하지 않음 | `NONE` |
| 503 | `LOGIN_UNAVAILABLE` | DB·Redis 실패, 적용 약관 없음 또는 완료 결과 불명. 자동 재전송 없이 로그인 재시작 | `RESTART_LOGIN` |

동의 소비·커밋·세션 실패의 처리 순서는 [동의 설계](account/TERMS_CONSENT_DESIGN.md#4-동의-완료의-원자성과-실패-복구)를 따른다.

## 폐기

`session_id` 원문을 JSON 본문으로 받는다. UUID나 해시를 대체 입력으로 받지 않는다.
[세션 계약](session/SESSION_DESIGN.md#로그아웃)에 따라 ID를 해시하고 해당 세션만 폐기한다.
부재·만료·이미 교체됨·이미 폐기됨은 `204`다. 이전 ID가 현재의 새 로그인을 지우지 않는다.
형식 오류는 `400 INVALID_SESSION_ID`, 저장소 오류·결과 불명은
`503 REVOCATION_UNCONFIRMED`다. 허용된 제한적 재시도도 같은 ID 조건을 유지한다.

## 본인 계정

응답은 `id`, `display_name`, `email`, `onboarding_completed`, `locale`이며 이메일이 없으면 `null`이다.
`onboarding_completed`는 온보딩 완료 시각이 기록되어 있으면 `true`인 boolean이다.
`locale`은 계정 언어 `ko`·`en`이며 기록한 적이 없으면 `null`이다. 모든 필드는 값이 없어도 생략하지 않는다.
조회·표시 이름 수정·언어 저장 응답에 모두 포함한다.
수정은 `display_name`만 허용한다. [계정 규칙](account/AUTH_ERD.md#2-계정-저장-규칙)을 유지한다.

```json
{
  "id": "0199b7a0-0000-7000-8000-000000000001",
  "display_name": "Writer",
  "email": "writer@example.com",
  "onboarding_completed": false,
  "locale": null
}
```

### 계정 언어

`PUT /auth/users/me/locale`은 `{"locale": "en"}`처럼 `locale` 하나만 받는다. 성공하면 `200`,
`Cache-Control: no-store`와 `GET /auth/users/me`와 같은 계정 본문을 반환한다. 저장된 값과 같으면
수정 시각을 바꾸지 않으므로 반복 호출해도 된다. `X-User-Id` 처리는 다른 본인 계정 API와 같다.

본문 누락, `locale` 누락·`null`·비문자열, 알 수 없는 필드, 정확히 `ko`·`en`이 아닌 값(`EN`, ` en`, `en-US` 등)은
`400 INVALID_REQUEST`다. 계정이 없으면 `404 USER_NOT_FOUND`, 저장소 장애는 `503 ACCOUNT_UNAVAILABLE`이다.
Auth는 언어를 저장·반환만 하며 화면 언어 선택과 최초 기록 시점은 BFF·프론트가 정한다.

### 온보딩 완료

`PUT /auth/users/me/onboarding`은 본문을 받지 않고 `204`와 `Cache-Control: no-store`를 반환한다.
완료 시각이 비어 있을 때만 현재 시각을 기록하므로 반복 호출해도 처음 완료 시각을 유지한다.
도움말의 온보딩 다시 보기는 프론트 동작이며 이 값을 초기화하지 않는다.
계정이 없으면 `404 USER_NOT_FOUND`, 저장소 장애는 `503 ACCOUNT_UNAVAILABLE`이다.

### 회원 탈퇴

`DELETE /auth/users/me`는 유예기간 없이 계정을 삭제하고 `204`와 `Cache-Control: no-store`를 반환한다.
콘텐츠 데이터 삭제와 쿠키 삭제는 BFF가 조율하며, Auth는 아래 순서만 담당한다.

1. `by-user` 인덱스로 현재 로그인 세션을 폐기한다. [세션 계약](session/SESSION_DESIGN.md#회원-탈퇴의-사용자-세션-폐기)을 따른다.
2. 폐기를 확인한 뒤 한 트랜잭션에서 약관 동의 기록·외부 신원 연결·계정을 삭제한다.
   공용 약관 원문(`terms_versions`)은 유지한다.
3. 커밋 후 같은 폐기를 한 번 더 실행해 삭제 도중 발급된 세션을 제거한다. 이 단계의 실패는 응답을 바꾸지 않는다.

세션 폐기를 확인하지 못하면 DB를 변경하지 않고 `503 ACCOUNT_UNAVAILABLE`을 반환한다.
DB 삭제 실패도 같은 오류이며 트랜잭션이 롤백되므로 재시도할 수 있다. 계정이 이미 없으면
`404 USER_NOT_FOUND`이며 BFF는 탈퇴 완료로 처리한다.

탈퇴한 계정의 동의 대기는 사용자 UUID에 연결되어 있다. 조회·완료 시 계정을 확인하므로
남은 대기는 `401 CONSENT_REQUEST_INVALID`로 거절되고 세션으로 완료되지 않는다.
같은 Google 계정으로 다시 로그인하면 새 UUID의 신규 가입이 되며 새 동의가 필요하다.
세션 발급 직후에도 계정 존재를 다시 확인해, 탈퇴와 경합한 로그인·동의 완료가 만든 세션은
폐기하고 `503 LOGIN_UNAVAILABLE`로 응답한다.

## 오류 계약

본문은 `code`, `message`, `next_action`이다. 호출자는 code로 분기한다.
next_action은 안내이며 세션 상태 변경이나 명령 실행 여부의 증거가 아니다.

| HTTP | code | 조건 | next_action |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | JSON·필수 입력·헤더·수정 필드·계정 언어 값·지원하지 않는 메서드 오류 | `NONE` |
| 400 | `INVALID_DISPLAY_NAME` | 표시 이름 검증 실패 | `NONE` |
| 400 | `INVALID_SESSION_ID` | 폐기 ID 누락·형식 오류 | `NONE` |
| 400 | `OAUTH_REQUEST_INVALID` | 임시 상태·브라우저 연결·state 검증 실패 | `RESTART_LOGIN` |
| 400 | `OAUTH_LOGIN_DENIED` | Google 로그인 취소·거절 | `RESTART_LOGIN` |
| 401 | `OAUTH_IDENTITY_INVALID` | Google 신원 검증 실패 | `RESTART_LOGIN` |
| 401 | `USER_CONTEXT_REQUIRED` | 본인 계정 사용자 헤더 누락 | `RELOGIN` |
| 404 | `USER_NOT_FOUND` | 계정 없음 | `RELOGIN` |
| 503 | `LOGIN_UNAVAILABLE` | DB·Redis·Google 장애 또는 세션 생성 결과 불명 | `RESTART_LOGIN` |
| 503 | `REVOCATION_UNCONFIRMED` | 세션 폐기 완료 미확인 | `NONE` |
| 503 | `ACCOUNT_UNAVAILABLE` | 계정 저장소 장애, 탈퇴 전 세션 폐기 미확인 | `RETRY_LATER` |
| 500 | `INTERNAL_ERROR` | 로그인 생성 단계의 손상 레코드·내부 결함 등 미분류 오류 | `NONE` |

BFF의 보호 요청 검증·연장 실패는 [BFF 제공 API](../../loresentry-gateway/docs/API.md#보호-api-인증-실패)의
세션 오류다. 폐기 실패는 브라우저 쿠키 처리와 별개이며
[BFF 로그아웃](../../loresentry-gateway/docs/auth/LOGOUT_FLOW.md)을 따른다.

분류되지 않은 예외는 `500 INTERNAL_ERROR`와 고정된 일반 메시지로 응답한다.
스택·SQL·제공자 오류 원문은 반환하지 않는다. 구현과 로그 처리의 기준은
[오류 전달 경계](ARCHITECTURE.md#오류-전달-경계), 검증은 [테스트 계획](implementation/TEST_PLAN.md#오류-처리)을 따른다.

## 진단 API

| 메서드·경로 | 용도 |
|---|---|
| `GET /health` | 프로세스 상태 확인 |
| `GET /health/db` | DB 연결 확인 |
| `GET /` | 서비스 이름 |

진단 API도 클러스터 내부 접근 범위를 따른다.
