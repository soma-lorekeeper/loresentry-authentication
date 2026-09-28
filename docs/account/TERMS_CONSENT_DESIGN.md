# Auth 약관 동의 설계

> **책임:** MVP의 약관 버전·동의 기록, 서버 동의 대기와 Auth 내부 API를 정한다.
>
> **확인할 때:** Google 인증 후 계정·동의 검사·저장과 내부 API를 구현할 때.
>
> **관련 기준:** 공개 문안은 [서비스 이용약관](../privacy/TERMS_OF_SERVICE.md)과 [개인정보 처리방침](../privacy/PRIVACY_POLICY.md)을 본다.

**미구현 목표 설계다.** Google 인증 후 계정은 먼저 생성·조회하고, 현재 시행 중인 최신
서비스 이용약관에 동의했을 때 로그인 세션을 발급한다. 동의하지 않은 계정도 보관한다.

동의 대상은 `SERVICE_TERMS` 하나다. 개인정보 처리방침은 열람 링크로 제공한다.
MVP는 약관 조회·동의 완료 API만 추가한다. 취소 API, 약관 관리 화면, 선택 동의,
기존 로그인 세션에 대한 즉시 재동의 강제는 포함하지 않는다.
이용 대상은 [이용약관](../privacy/TERMS_OF_SERVICE.md#제2조-가입과-계정)의 만 14세 이상이다.
MVP에는 별도 연령 확인 체크박스·생년월일 수집·휴대폰 인증·보호자 동의 기능을 추가하지 않는다.
Google 로그인 성공을 연령 검증 완료로 취급하지 않으며, 아동 가입 대응과 미성년자 이용 조건은 출시 전 검토한다.
브라우저 연동은 [BFF 로그인 흐름](../../../loresentry-gateway/docs/auth/LOGIN_FLOW.md#약관-동의-연동-mvp-미구현),
화면 입력은 [프론트 동의 계약](../../../loresentry-gateway/docs/FRONTEND_AUTH_CONTRACT.md#약관-동의-mvp-미구현)을 따른다.

## 1. 약관 원문과 동의 기록

Auth PostgreSQL에 다음 두 테이블을 추가한다. 기존 계정 구조는 [계정 ERD](AUTH_ERD.md)를 따른다.

| 테이블 | 컬럼 | 제약·역할 |
|---|---|---|
| `terms_versions` | `id uuid` | PK |
| | `terms_type varchar`, `version varchar` | NOT NULL, 두 컬럼 조합 UNIQUE. 유형은 `SERVICE_TERMS` |
| | `title text`, `content text` | NOT NULL. 제목과 일반 텍스트 전문 |
| | `published_at timestamptz`, `effective_at timestamptz` | NOT NULL, 공개 시각 ≤ 시행 시각. 유형·시행 시각 조합 UNIQUE |
| `user_terms_acceptances` | `user_id uuid` | FK → `users.id`, ON DELETE CASCADE |
| | `terms_version_id uuid` | FK → `terms_versions.id` |
| | `accepted_at timestamptz` | NOT NULL. 서버의 동의 접수 시각 |

동의 테이블의 PK는 `(user_id, terms_version_id)`이며 중복 저장 시 최초 동의 시각을 유지한다.
동의 기록에 원문을 복제하지 않는다. 탈퇴 시 해당 사용자의 동의 기록도 계정과 함께 삭제하고,
사용자 공통 데이터인 약관 버전과 원문은 유지한다.

현재 적용 버전은 서버 시각 기준 `published_at`과 `effective_at`이 모두 도래한 행 중
`effective_at`이 가장 최신인 버전이다. 버전 문자열의 크기로 비교하지 않는다.
미래 시행 버전은 미리 등록할 수 있지만, 시행 전에는 동의를 요구하지 않는다.
로그인 및 동의 완료 처리에서 현재 버전을 확인한다. 이미 발급한 로그인 세션은 그대로 유지한다.

최초 약관은 INSERT 시 `published_at = effective_at = CURRENT_TIMESTAMP`로 저장한다.
두 값은 같은 등록 트랜잭션의 시각이며 재기동·재조회 시 다시 계산하지 않는다. 운영 DB 등록과
실제 약관 공개·동의 절차 시작을 같은 배포에 맞춘다. 로컬·테스트 DB의 등록 시각은 운영 시행일이 아니다.
공개 문안의 날짜는 저장된 시각을 한국 시간으로 표시하며, DB에는 `timestamptz`로 보관한다.

개정 약관의 `published_at`은 실제 공개와 맞춘 DB 등록 시각으로 두고, `effective_at`은
[사전 안내 기간](../privacy/TERMS_OF_SERVICE.md#제10조-문의와-약관-변경)을 만족하는 미래 시각으로 지정한다.
최초 등록의 두 날짜 동일 규칙을 개정에 일괄 적용하지 않는다.

약관 최초 등록·개정은 **새 Flyway 버전 SQL의 INSERT**로 처리한다. 공개된 본문과 적용된
마이그레이션은 수정하지 않는다. 약관 테이블·최초 원문을 배포한 뒤 동의 검사를 활성화하며,
적용 가능한 약관이 없으면 로그인을 완료하지 않고 `LOGIN_UNAVAILABLE`을 반환한다.

## 2. Google 인증 이후의 동의 흐름

1. `prepare`는 기존 OAuth 준비만 수행한다. 동의 ID나 약관 버전을 받지 않는다.
2. 콜백에서 Google 신원을 검증하고 `accountRegistration.register(identity)`로 계정을
   생성·조회한다. 이 계정 트랜잭션은 동의 검사 전에 커밋한다.
3. `LoginService.callback()`에서 `register()` 반환 후, `createSession()` 호출 전에
   현재 약관 버전의 동의 기록을 확인한다. 동의 기록이 있으면 기존 방식으로 세션을 발급한다.
4. 동의가 없으면 계정에 연결된 동의 대기를 생성하고 `TERMS_REQUIRED`를 BFF에 반환한다.
   이 분기에서는 로그인 세션을 발급하지 않는다.
5. 이후 약관 조회·동의 완료는 별도 API로 처리한다. 콜백에는 약관 전문을 넣지 않고,
   완료 시 Google 콜백을 다시 호출하지 않는다.
6. Auth는 대기 상태·계정·현재 버전을 검사하고 동의 기록을 저장한다.
   DB 커밋 후 기존 [세션 발급 계약](../session/SESSION_DESIGN.md)에 따라 새 로그인 세션을 발급한다.

## 3. 서버 동의 대기

Auth는 Redis의 `auth:consent:by-id:<SHA-256 해시>`에 `user_id`, `terms_version_id`,
`created_at`, `expires_at`을 저장한다. Google 이름·이메일은 복제하지 않는다.
`consent_request_id`는 CSPRNG 32바이트의 padding 없는 Base64URL이며 원문은 저장하지 않는다.
수명은 생성부터 **30분의 절대 만료**이고 조회·새로고침으로 연장하지 않는다.

이 식별자는 약관 조회·완료에만 사용할 수 있으며 로그인 세션으로 인증할 수 없다.
Auth는 대기에 저장된 `user_id`로 계정을 선택한다. BFF는 동의 대기 Redis에 직접 접근하지 않는다.
전달받은 식별자 원문을 로그에 기록하지 않는다.

화면을 닫아도 계정과 대기는 삭제하지 않는다. 새 Google 로그인에서는 새로 검증한 신원으로
계정·동의를 다시 확인하고, 동의가 필요하면 새 대기를 생성한다. 이전 대기는 별도로 찾아
삭제하지 않고 TTL로 만료시킨다. 부재·만료·소비한 대기는 재사용하지 않는다.
브라우저 쿠키와 화면 종료 처리는 BFF가 관리한다.

## 4. API 계약

아래는 BFF가 호출할 Auth 내부 API의 미구현 계약이다. 현재 제공 API는 [Auth API](../API.md),
BFF의 입력 구성과 응답 사용은
[BFF 동의 호출](../../../loresentry-gateway/docs/API_CALLS.md#약관-동의-호출-mvp-미구현)에서 확인한다.
공통 JSON·시각·오류 형식을 따르고 모든 동의 응답에 `Cache-Control: no-store`를 적용한다.

### OAuth 콜백 확장

Auth의 기존 `POST /auth/oauth/google/callback`은 `200` 응답을 다음 두 종류로 구분한다.

| status | 반환 필드 |
|---|---|
| `AUTHENTICATED` | 기존 `session_id`, `expires_at`, `login_request_consumed` |
| `TERMS_REQUIRED` | `consent_request_id`, `expires_at`, `login_request_consumed: true` |

`expires_at`은 각 분기에서 발급한 세션 또는 대기의 만료다. 두 ID를 함께 반환하지 않는다.
기존 오류의 `login_request_consumed` 의미는 유지한다. Auth·BFF·프론트가 새 분기를 처리할 수
있도록 맞춰 배포한다.

### 약관 조회와 동의 완료

| 기능 | Auth 경로 | 내부 입력 | 성공 |
|---|---|---|---|
| 약관 조회 | `GET /auth/terms` | `X-Consent-Request-Id` 헤더 | `200`, 아래 약관 필드 |
| 동의 완료 | `POST /auth/terms/accept` | JSON의 `consent_request_id`, `terms_version_id` | `200`, `session_id`, `expires_at` |

조회 응답은 `terms_version_id`, `version`, `title`, `content`, `effective_at`, `expires_at`이다.
마지막 필드는 동의 대기의 만료다. Auth는 현재 적용 원문을 반환하고 대기의 대상 버전을
그 버전으로 갱신하되 만료는 유지한다. 조회 도중 소비된 대기를 재생성하지 않는다.

완료 입력의 버전 ID는 사용자가 확인한 버전이다. Auth는 대기에 연결된 버전 및 현재 적용 버전과
모두 일치할 때만 저장한다. 성공 응답은 `session_id`, `expires_at`이며 BFF 내부에서만 사용한다.

### 오류와 완료 처리

| HTTP | code | 처리 | next_action |
|---|---|---|---|
| 400 | `INVALID_REQUEST` | 입력·UUID 형식 오류. 동의 저장 없음 | `NONE` |
| 401 | `CONSENT_REQUEST_INVALID` | 대기 부재·만료·소비됨 또는 연결 계정 없음 | `RESTART_LOGIN` |
| 409 | `TERMS_VERSION_MISMATCH` | 제출·대기·현재 버전 불일치. 대기는 소비하지 않음 | `NONE` |
| 503 | `LOGIN_UNAVAILABLE` | DB·Redis 실패, 적용 약관 없음 또는 완료 결과 불명. 자동 재전송 없이 로그인 재시작 | `RESTART_LOGIN` |

완료 요청은 입력과 버전을 먼저 검사하고, Redis에서 대상 버전·만료를 다시 확인하며
대기를 원자적으로 소비한다. 소비에 성공한 요청 하나만 동의 저장·세션 발급을 진행한다.
검사 도중 다른 조회가 대상 버전을 바꾸면 소비하지 않고 `409`를 반환한다.

동의 저장 트랜잭션이 실패하면 동의 기록만 롤백하고 이미 생성된 계정은 유지한다.
동의 커밋 후 세션 저장 실패·응답 유실이 발생하면 동의 기록을 유지하고 새 로그인으로 복구한다.
이미 소비한 대기를 복원하거나 같은 요청으로 세션을 반복 발급하지 않는다.

검증 시나리오는 [Auth 테스트 계획](../implementation/TEST_PLAN.md#약관-동의)을 따른다.
브라우저 API·쿠키·화면 검증은 [BFF 검증 범위](../../../loresentry-gateway/docs/auth/LOGIN_FLOW.md#검증-범위)에서 관리한다.
