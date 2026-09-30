# Auth 서버 코드 읽기

> **책임:** 현재 코드의 요청 경로·객체 구성·관련 테스트 위치를 안내한다.
>
> **확인할 때:** 구현을 읽거나 변경할 클래스·메서드를 찾을 때.
>
> **관련 기준:** 설계 기준과 계층 책임은 [서버 구조](ARCHITECTURE.md)를 본다.

요청 흐름은 컨트롤러에서 시작하고, 객체를 어떻게 만들고 연결하는지 궁금할 때는
`config`를 읽는다. 요청 처리와 서버 시작 시 초기화를 구분해서 따라간다.

패키지의 책임·의존 방향은 [서버 구조](ARCHITECTURE.md#패키지와-의존-방향)를 따른다.
아래 링크는 현재 소스의 클래스·메서드 위치를 안내한다.

## 로그인 준비 요청

1. [AuthController.prepare()](../src/main/java/com/loresentry/authentication/adapter/in/web/AuthController.java)는
   요청을 받고 `login.prepare()`를 호출한 뒤 결과를 응답 DTO로 변환한다.
2. [LoginUseCase](../src/main/java/com/loresentry/authentication/application/port/in/LoginUseCase.java)는
   준비와 콜백 기능을 정의하는 입력 포트다.
3. [LoginService.prepare()](../src/main/java/com/loresentry/authentication/application/service/LoginService.java)는
   애플리케이션 내부의 `oauthRequests.prepare()`에 임시 요청 관리를 맡긴다.
4. [OAuthRequests](../src/main/java/com/loresentry/authentication/application/service/OAuthRequests.java)의
   `createLoginState()`는 설정, 생성·만료 시각, state·nonce·PKCE 검증값을 준비한다.
   `saveLoginRequest()`는 요청 ID를 생성해 저장하고 Google 인증 URL과 함께 반환한다.
5. 저장은 [OAuthStateStore](../src/main/java/com/loresentry/authentication/application/port/out/OAuthStateStore.java)를,
   설정과 인증 URL 생성은 [OidcClient](../src/main/java/com/loresentry/authentication/application/port/out/OidcClient.java)를
   통해 각 어댑터에 맡긴다.

## Google 인증 후 콜백

`LoginService.callback()`에서 다음 순서를 읽는다.

1. `consumeLoginRequest()`로 임시 요청을 검증하고 소비한다.
2. 사용자가 Google 인증을 거부했다면 로그인 거부 오류를 반환한다.
3. `oidcClient.exchange()`로 인증 코드를 교환하고 신원을 확인한다.
4. `accountRegistration.register()`로 계정을 연결하고 DB 트랜잭션 완료를 기다린다.
5. `TermsLoginGate`가 현재 시행 원문과 계정의 동의 기록을 확인한다. 미동의 계정은 대기를 반환한다.
6. 로그인 완료는 `SessionIssuance.create()`가 난수 ID와 활성 세션을 생성한다.

`OAuthRequests.consume()`는 입력 검사, 저장된 요청 조회·검증, 소비, 소비한 값의 재검증을
순서대로 수행한다. 오류에 붙는 소비 상태는 재시도 판단에 영향을 주므로 이 경계를 유지한다.

## 약관 조회와 완료

- [TermsController](../src/main/java/com/loresentry/authentication/adapter/in/web/TermsController.java)는 조회·동의 요청을 서비스에 전달한다.
- `TermsQueryService`는 현재 원문을 조회하고 대기의 대상 버전을 만료 연장 없이 갱신한다.
- `TermsAcceptService`는 대기를 일회 소비하고 동의 기록 커밋 후 `SessionIssuance.create()`를 호출한다.
- `JdbcTermsVersionStore`·`JdbcTermsAcceptanceStore`는 원문 조회·동의 기록 저장을 담당한다.
- `RedisConsentRequestStore`와 `redis/consent-request.lua`는 절대 만료·버전 갱신·일회 소비를 담당한다.
- `TermsConfiguration`은 서비스와 동의 검사 활성화 설정을 연결한다.

## 세션 생성과 폐기

- [SecureSessionIds](../src/main/java/com/loresentry/authentication/adapter/out/id/SecureSessionIds.java)는
  32바이트 난수 ID를 생성한다. `domain.SessionId`가 정규 형식을 검증하고 SHA-256 해시를 계산한다.
- [RedisLoginSessionStore](../src/main/java/com/loresentry/authentication/adapter/out/redis/RedisLoginSessionStore.java)는
  `LoginSessionStore`를 구현한다. `redis/login-session.lua`는 두 인덱스를 생성·교체하고,
  `redis/revoke-session.lua`는 입력 ID에 해당하는 세션만 조건부 폐기한다.
- [SessionController](../src/main/java/com/loresentry/authentication/adapter/in/web/SessionController.java)는
  폐기 요청을 `RevokeSessionService`에 전달한다. 서비스는 ID를 검증하고 제한된 예산 안에서 폐기를 재시도한다.

수명·원자성·실패 규칙은 [세션 계약](session/SESSION_DESIGN.md)을 따른다.
보호 요청의 검증과 활동 TTL 연장은 BFF에서 수행한다.

## 서버 시작 시 객체 구성

[LoginConfiguration](../src/main/java/com/loresentry/authentication/config/LoginConfiguration.java)은
`OAuthRequests`, `LoginService`, `SecureSessionIds`, `RevokeSessionService`를 만들고 연결한다.
[GoogleConfiguration](../src/main/java/com/loresentry/authentication/config/GoogleConfiguration.java)은
설정값으로 `GoogleOidcClient`를 생성한다.

[GoogleOidcClient](../src/main/java/com/loresentry/authentication/adapter/out/google/GoogleOidcClient.java)의
생성자와 초기화 메서드는 다음 역할을 나눈다.

| 위치 | 역할 |
| --- | --- |
| 생성자 | 클라이언트 등록 정보, HTTP 클라이언트, 인증 처리 객체를 연결 |
| `createClientRegistration()` | ID·비밀값·콜백 주소·Google 주소 등의 설정 객체 생성 |
| `createAuthenticationProvider()` | 토큰 교환과 ID 토큰 검증을 연결 |
| `createTokenClient()` | 토큰 요청의 HTTP 전송과 응답 변환 구성 |
| `createIdTokenDecoder()` | 공개키와 서명 알고리즘, 발급자·대상·시간 검증 구성 |

로그인 요청 처리 중에는 이미 구성된 객체를 사용한다. `settings()`는 `registration`에서
필요한 값만 꺼내 `OidcClient.Settings`로 반환한다. `authorizationUrl()`은 인증 URL을
만들고, `exchange()`는 통신 작업의 실행·대기·실패 처리를 담당한다.
실제 코드 교환과 검증은 `exchangeAndVerifyIdentity()`에서 읽을 수 있다.

## 검증 위치

- `LoginServiceTest`, `FailureRegressionTest`: 로그인 순서와 실패·소비 상태.
- `SessionIdTest`, `LoginSessionStoreTest`, `RevokeSessionServiceTest`: ID 형식, Redis 인덱스·경쟁과 조건부 폐기.
- `GoogleOidcClientTest`: 인증 URL, PKCE·nonce, ID 토큰 검증과 통신 실패.
- `AuthControllerTest`, `FullLoginFlowTest`: HTTP 계약과 통합 로그인 흐름.
- `TermsSchemaTest`, `TermsVersionStoreTest`, `MigrationTest`: 원문·기록·마이그레이션.
- `ConsentQueryTest`, `ConsentLoginFlowTest`, `TermsLoginServiceTest`, `TermsAcceptServiceTest`: 대기·로그인·동시 소비·실패 복구.
- `ArchitectureTest`: 계층 간 의존성 방향.

실행 방법과 검증 범위는 [프로젝트 README](../README.md#test)를 따른다.
