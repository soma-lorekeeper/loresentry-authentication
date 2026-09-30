# Auth 약관 동의 설계

> **책임:** 약관 버전·동의 기록·서버 동의 대기와 완료 처리의 원자성을 정한다.
>
> **확인할 때:** Google 인증 후 계정·동의 검사·저장과 내부 API를 구현할 때.
>
> **관련 기준:** 공개 문안은 [서비스 이용약관](../privacy/TERMS_OF_SERVICE.md)과 [개인정보 처리방침](../privacy/PRIVACY_POLICY.md)을 본다.

Google 인증 후 계정은 먼저 생성·조회하고, 동의 검사가 활성화되어 있으면 현재 시행 중인 최신
서비스 이용약관에 동의했을 때 로그인 세션을 발급한다. 동의하지 않은 계정도 보관한다.

동의 대상은 `SERVICE_TERMS` 하나다. 개인정보 처리방침은 열람 링크로 제공한다.
MVP는 약관 조회·동의 완료 API만 추가한다. 취소 API, 약관 관리 화면, 선택 동의,
기존 로그인 세션에 대한 즉시 재동의 강제는 포함하지 않는다.
이용 대상은 [이용약관](../privacy/TERMS_OF_SERVICE.md#제2조-가입과-계정)의 만 14세 이상이다.
MVP에는 별도 연령 확인 체크박스·생년월일 수집·휴대폰 인증·보호자 동의 기능을 추가하지 않는다.
Google 로그인 성공을 연령 검증 완료로 취급하지 않으며, 아동 가입 대응과 미성년자 이용 조건은 출시 전 검토한다.
브라우저 연동은 [BFF 로그인 흐름](../../../loresentry-gateway/docs/auth/LOGIN_FLOW.md#약관-동의-연동),
화면 입력은 [프론트 동의 계약](../../../loresentry-gateway/docs/FRONTEND_AUTH_CONTRACT.md#약관-동의)을 따른다.

## 1. 약관 원문과 동의 기록

Auth PostgreSQL의 V3 마이그레이션이 다음 두 테이블을 생성한다. 기존 계정 구조는 [계정 ERD](AUTH_ERD.md)를 따른다.

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

최초 원문 `v0`는 `V4__publish_service_terms_v0.sql`이 등록한다. 문서의 내부 안내를 제외한
일반 텍스트 전문이며, 개인정보 처리방침 링크는 공개 URL로 저장한다.
최초 약관은 INSERT 시 `published_at = effective_at = CURRENT_TIMESTAMP`로 저장한다.
두 값은 같은 등록 트랜잭션의 시각이며 재기동·재조회 시 다시 계산하지 않는다. 운영 DB 등록과
실제 약관 공개·동의 절차 시작을 같은 배포에 맞춘다. 로컬·테스트 DB의 등록 시각은 운영 시행일이 아니다.
공개 문안의 날짜는 저장된 시각을 한국 시간으로 표시하며, DB에는 `timestamptz`로 보관한다.

개정 약관의 `published_at`은 실제 공개와 맞춘 DB 등록 시각으로 두고, `effective_at`은
[사전 안내 기간](../privacy/TERMS_OF_SERVICE.md#제10조-문의와-약관-변경)을 만족하는 미래 시각으로 지정한다.
최초 등록의 두 날짜 동일 규칙을 개정에 일괄 적용하지 않는다.

약관 최초 등록·개정은 **새 Flyway 버전 SQL의 INSERT**로 처리한다. 공개된 본문과 적용된
마이그레이션은 수정하지 않는다. 약관 테이블·최초 원문을 배포한 뒤 동의 검사를 활성화하며,
`AUTH_TERMS_ENABLED`는 기본 `false`다. 확정 원문 공개와 세 서비스의 호환 배포를 마친 뒤
`true`로 설정한다. 활성화 상태에서 적용 가능한 약관이 없으면 로그인을 완료하지 않고
`LOGIN_UNAVAILABLE`을 반환한다. 배포 순서는 [배포·복구](../../../loresentry-gateway/docs/ROLLOUT.md#약관-동의-활성화)를 따른다.

## 2. Google 인증 이후의 동의 흐름

1. `prepare`는 기존 OAuth 준비만 수행한다. 동의 ID나 약관 버전을 받지 않는다.
2. 콜백에서 Google 신원을 검증하고 `accountRegistration.register(identity)`로 계정을
   생성·조회한다. 이 계정 트랜잭션은 동의 검사 전에 커밋한다.
3. `LoginService.callback()`에서 `register()` 반환 후, `SessionIssuance.create()` 호출 전에
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

## 4. 동의 완료의 원자성과 실패 복구

내부 HTTP 필드·상태·오류는 [Auth 제공 API](../API.md#약관-조회와-동의-완료),
BFF의 입력 구성은 [호출 API](../../../loresentry-gateway/docs/API_CALLS.md#약관-동의-호출)를 따른다.

완료 요청은 입력과 버전을 먼저 검사하고, Redis에서 대상 버전·만료를 다시 확인하며
대기를 원자적으로 소비한다. 소비에 성공한 요청 하나만 동의 저장·세션 발급을 진행한다.
검사 도중 다른 조회가 대상 버전을 바꾸면 소비하지 않고 `409`를 반환한다.

동의 저장 트랜잭션이 실패하면 동의 기록만 롤백하고 이미 생성된 계정은 유지한다.
동의 커밋 후 세션 저장 실패·응답 유실이 발생하면 동의 기록을 유지하고 새 로그인으로 복구한다.
이미 소비한 대기를 복원하거나 같은 요청으로 세션을 반복 발급하지 않는다.

검증 시나리오는 [Auth 테스트 계획](../implementation/TEST_PLAN.md#약관-동의)을 따른다.
브라우저 API·쿠키·화면 검증은 [BFF 검증 범위](../../../loresentry-gateway/docs/auth/LOGIN_FLOW.md#검증-범위)에서 관리한다.
