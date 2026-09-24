# Auth 테스트 계획

현재 구현의 검증 항목과 실행 결과다. 운영 배포와 실제 Google·브라우저 연동은 별도로 검증한다.

## 실행 결과

2026-09-24 LOREKEEPER-535의 Auth 단일 세션 구현은 `work/LOREKEEPER-545`의
`2bdfaf0`에서 전체 빌드를 통과했다. Redis 7.4와 운영 이미지와 같은 Valkey 9.0.6에서
각각 **152개 테스트, 실패·오류·건너뜀 0건**이다. Java 21, PostgreSQL 18.4와 테스트 Google 서버를 사용했다.
Lua·TIME·PXAT·직렬화 및 독립 연결의 경쟁을 검증했다. BFF의 실제 보호 API 차단,
운영 ACL·네트워크·배포를 검증한 결과는 아니다. [전환 인계](../../../docs/auth/implementation/SESSION_HANDOFF.md)에 남은 조건을 기록했다.

### 이전 검증 기록 — 2026-09-22

2026-09-22 `loresentry-authentication`의 로컬 `main` 커밋 `5fadd4f`에 반영한 코드로
`./gradlew --no-daemon clean build`를 실행해 **129개 테스트가 모두 통과했다.**
실패·오류·건너뜀은 0건이다. Java 21, Spring Boot 4.1.1과 Testcontainers의
PostgreSQL 18.4·Redis 7.4를 사용했고, Google 응답은 테스트 HTTP/JWK 서버로 제어했다.

검증 항목별 테스트 클래스와 재현 방법은 Auth 저장소의 `TEST_COVERAGE.md`에 있다.
실제 Google 동의 화면, 브라우저·BFF 연동, 운영 자격 증명·네트워크와 V2 배포는
이 결과에 포함하지 않는다.

## 구조와 테스트 경계

기준: [구조와 테스트 경계](../ARCHITECTURE.md).

- 코어 단위 테스트는 가짜 포트와 고정 `Clock`으로 분기·실패 처리·호출 순서를 검증한다.
- 어댑터 통합 테스트는 실제 PostgreSQL·Redis에서 트랜잭션, 제약, TTL과 일회성 소비를 검증한다.
- Google·JWT 어댑터는 검증 실패를 포함해 포트 계약을 확인한다.
- 패키지 의존 규칙은 ArchUnit 테스트로 검사한다.

## 오류 처리

기준: [오류 구현 규칙](IMPLEMENTATION_NOTES.md#오류-처리), [API 오류 계약](../INTERNAL_API.md#오류-계약).

- 정의된 실패와 잘못된 JSON·입력이 계약의 HTTP 상태·`code`·`next_action`으로 변환되는지 확인한다.
- 갱신 명령 미실행·결과 불명·서명 실패·손상된 레코드가 각각 계약의 응답으로 유지되는지 확인한다.
- OAuth 콜백 실패에서도 `login_request_consumed`의 `true`·`false`·`null`이 보존되고 다른 API에서는 생략되는지 확인한다.
- 예상하지 못한 예외가 고정된 500 응답으로 변환되고, 응답·로그에 토큰·비밀값이 노출되지 않는지 확인한다.
- MVC 이전 오류 처리에서도 같은 응답 형식을 사용하는지 확인한다.

## 계정 저장

기준: [계정 저장](../account/PERSISTENCE_DESIGN.md).

PostgreSQL 통합 테스트에서 UUID v7 신규 저장, 복합 PK 충돌 시 전체 롤백과 재조회,
표시 이름 보존·이메일 갱신, DB 커밋 후 세션 교체 실패 시 계정 유지를 확인한다.
스키마는 테스트에서도 Flyway로 생성하고 Hibernate 검증을 통과시킨다.

### 마이그레이션 실행 방식

- Spring 통합 테스트는 `src/test/resources/application.properties`에서
  `spring.flyway.enabled=true`로 자동 마이그레이션을 켠다. 빈 테스트 DB에
  Flyway가 스키마를 만든 뒤 Hibernate의 `ddl-auto=validate`가 구조를 검사한다.
- `MigrationTest`는 Spring 컨텍스트 없이 Flyway를 직접 호출한다. 새 DB의 V1→V2
  적용과 V1만 적용된 빈 테이블의 V2 전환을 각각 검증한다.
- 전환 뒤 `users`·`oauth_identities` 구조, V1 체크섬 보존, 마이그레이션 재실행 시
  추가 SQL이 적용되지 않는지를 확인한다. `AccountSchemaTest`는 V2 적용 이력과
  실제 JPA 저장·조회를 검사한다.

자동 실행을 끄면 Spring Boot가 기동 중 마이그레이션을 호출하지 않는다.
Hibernate의 `validate`는 테이블을 만들지 않으므로 현재 통합 테스트에서는 자동 실행이
필요하다. 이 설정은 `MigrationTest`의 직접 `Flyway.migrate()` 호출을 막지 않는다.

## OAuth 상태

기준: [OAuth 상태](../login/OAUTH_STATE.md).

state 불일치 시 상태가 유지되고, 만료·부재 상태는 거절되는지 확인한다.
동시 콜백 중 하나만 소비에 성공하는지, PKCE·nonce 검증 실패 시 계정을 생성하거나 토큰을 발급하지 않는지 검증한다.

## JWT

기준: [JWT](../token/JWT_DESIGN.md).

AT·RT의 사용 대상과 토큰 종류를 서로 바꾸거나 서명·필수 필드·키 식별자가 유효하지 않으면
거절하는지 확인한다. 만료·미래 발급 시각의 오차 허용 경계와 키 교체 후 이전 토큰 거절도 검증한다.

## Refresh Token

기준: [Refresh Token](../token/REFRESH_TOKEN_DESIGN.md).

- 새 로그인마다 sid가 바뀌고 이전 RT를 거절하며, 새 AT sid가 저장된 sid와 일치하는지 확인한다.
- 같은 RT의 동시 재발급 중 하나만 갱신에 성공하고, 실패 요청이 상태를 바꾸지 않는지 확인한다.
- 독립 연결에서 로그인·재발급·폐기의 양쪽 실행 순서와 동시 로그인·갱신을 검증한다.
- 별도 읽기 연결이 반복 갱신 중 키 부재를 관찰하지 않는지 확인한다.
- 교체 전 서명 실패, 명령 미실행·응답 유실에서 토큰 반환·자동 재시도·복구가 없는지 확인한다.
- 다른 사용자 세션, 회전 전 미만료 RT, 재시도 중 새 로그인·RT 만료를 검증한다.
- 만료·유실·손상 레코드, sid 없는 구 RT와 구 키 fallback 금지를 확인한다.
- 이전 AT sid와 활성 sid의 불일치를 검사하되 실제 BFF 차단 검증으로 기록하지 않는다.
- 재발급 응답 유실 후 이전 RT를 재사용하면 거절되는지 확인한다.
- 폐기 재시도의 횟수·시간 제한과 최종 실패·미확인 응답이 계약에 맞는지 확인한다.
