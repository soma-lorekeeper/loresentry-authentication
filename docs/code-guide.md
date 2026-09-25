# Auth 서버 코드 읽기

2026-09-26 세션 ID 로그인·폐기를 코드에 반영했다. 아래는 OAuth·계정 경계를
읽는 안내이며 세션 구현의 검증 결과는 implementation/의 580–590 기록을 따른다.

요청 흐름은 컨트롤러에서 시작하고, 객체를 어떻게 만들고 연결하는지 궁금할 때는
`config`를 읽는다. 요청 처리와 서버 시작 시 초기화를 구분해서 따라간다.

## 패키지별 역할

아래 경로는 `src/main/java/com/loresentry/authentication` 기준이다.

| 패키지 | 담당하는 작업 |
| --- | --- |
| `adapter/in/web` | HTTP 요청 수신, 응답과 오류 반환 |
| `adapter/in/web/dto`, `mapper` | HTTP 데이터 형식과 애플리케이션 입출력 사이의 변환 |
| `application/port/in` | 애플리케이션이 외부에 제공하는 기능의 계약 |
| `application/service` | 로그인·세션·계정 처리 순서와 애플리케이션 내부 협력 |
| `application/port/out` | 외부 인증·저장소·세션 생성 등에 필요한 계약 |
| `adapter/out` | Google, Redis, DB 등의 외부 연동 |
| `domain` | 사용자·외부 계정과 핵심 규칙 |
| `config` | 설정값을 받아 객체를 생성하고 Spring 빈으로 연결 |

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
5. 이후의 로그인 완료 단계는 [새 세션 계약](session/SESSION_DESIGN.md#로그인)에 맞춰
   구현을 교체해야 한다. 현재 코드의 세션 발급 결과를 새 API 응답으로 해석하지 않는다.

`OAuthRequests.consume()`는 입력 검사, 저장된 요청 조회·검증, 소비, 소비한 값의 재검증을
순서대로 수행한다. 오류에 붙는 소비 상태는 재시도 판단에 영향을 주므로 이 경계를 유지한다.

## 세션 구현 전환

현재 SessionStore·RedisSessionStore의 저장 형식·연산은 새 계약과 다르다.
Auth는 ID 생성·두 인덱스 생성/교체·조건부 폐기로, BFF는 ID 검증·활동 TTL 연장으로
전환해야 한다. 기존 코드의 클래스·메서드가 새 설계대로 동작한다고 가정하지 않는다.
[구현 노트](implementation/IMPLEMENTATION_NOTES.md#활성-세션-저장과-명령-경계)를 따른다.

## 서버 시작 시 객체 구성

[LoginConfiguration](../src/main/java/com/loresentry/authentication/config/LoginConfiguration.java)은
`OAuthRequests`와 `LoginService`를 만들고 연결한다.
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
- `GoogleOidcClientTest`: 인증 URL, PKCE·nonce, ID 토큰 검증과 통신 실패.
- `AuthControllerTest`, `FullLoginFlowTest`: HTTP 계약과 통합 로그인 흐름.
- `ArchitectureTest`: 계층 간 의존성 방향.

실행 방법과 검증 범위는 [프로젝트 README](../README.md#test)를 따른다.
