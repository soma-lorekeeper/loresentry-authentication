# Auth 구현 노트

현재 Auth 구현의 라이브러리·매핑·설정과 참고 설명을 모았다.
동작과 실패 정책은 각 설계 문서를 기준으로 하며, 코드와 계약의 차이는 해당 항목에 기록한다.

## 오류 처리

오류 코드·HTTP 상태·응답 필드는 [내부 API 오류 계약](../INTERNAL_API.md#오류-계약)을 기준으로 한다.
예외의 의미는 코어에서, HTTP 응답 변환은 `adapter.in.web`에서 관리한다.

| 위치 | 처리 책임 |
|---|---|
| 도메인·애플리케이션 | 업무 실패를 명시적인 예외로 표현. HTTP 상태·Spring 타입은 포함하지 않음 |
| 출력 어댑터 | DB·Redis·Google의 기술 예외를 포트가 정의한 실패로 변환 |
| 애플리케이션 서비스 | 작업 단계와 실행 결과를 고려해 유스케이스의 실패를 결정 |
| 웹 어댑터 | `@RestControllerAdvice`와 `@ExceptionHandler`로 기존 오류 응답에 매핑 |

업무·포트 예외는 Java `RuntimeException` 기반으로 정의한다. 컨트롤러마다 `try-catch`를 반복하지 않는다.
catch는 예외 변환, 정해진 복구·재시도 또는 처리 상태 보존이 필요한 경계에 둔다.
동시 가입 중복은 [롤백 후 예외 변환](#트랜잭션-bean과-예외-변환)을 따른다.

### 처리 상태 보존

Redis 타임아웃만 보고 전역 핸들러가 실패 단계를 추측하지 않는다.
세션 갱신 명령 미실행 확인과 결과 미확인을 포트와 서비스에서 구별해 전달한다.
갱신은 조건부 교체 한 번이며 별도 저장 실패 단계는 없다. 서명·손상된 레코드 오류는 500이다.
명령 미실행을 증명할 수 없으면 미실행으로 취급하지 않는다.
OAuth 콜백도 예외 전달 과정에서 소비 상태를 보존하고, 응답의 `login_request_consumed`에 반영한다.
미확인 값을 임의로 `false`로 바꾸지 않으며, 다른 API에는 해당 필드를 넣지 않는다.

### 응답과 로그

- JSON 해석·요청 형식·입력 검증 오류도 기존 API 계약으로 변환한다. 모든 `IllegalArgumentException`을 입력 오류로 간주하지 않는다.
- 분류되지 않은 예외는 `500 INTERNAL_ERROR`와 고정된 일반 메시지로 응답한다.
- 응답에는 허용한 필드와 정해진 메시지만 사용한다. 예외의 `getMessage()`·스택·SQL·제공자 오류를 그대로 반환하지 않는다.
- 예상하지 못한 오류의 원인과 스택은 비밀값을 제외해 서버에 기록하고, 같은 오류를 계층마다 중복 기록하지 않는다.
- MVC에 도달하기 전 필터의 오류는 전역 Advice가 처리한다고 가정하지 않는다. 별도 처리 지점에서도 웹 계층의 같은 응답 변환을 사용한다.

검증은 [오류 처리 테스트](TEST_PLAN.md#오류-처리)를 따른다.
Spring MVC의 예외 처리 범위는 [공식 문서](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html)를 참고한다.

## JPA와 Flyway

### 의존성과 기본 설정

- `spring-boot-starter-data-jpa`를 사용하고 Hibernate·Spring Data 버전은 프로젝트의 Spring Boot BOM을 따른다.
- PostgreSQL 드라이버와 현재 HikariCP 설정은 유지한다.
- `spring.jpa.open-in-view=false`로 설정한다. 영속성 어댑터는 트랜잭션 안에서 엔티티를 코어 모델·조회 결과로 변환한다. 웹 어댑터가 이를 HTTP 응답 DTO로 변환한다.
- `spring.jpa.hibernate.ddl-auto=validate`로 설정한다. 테이블 생성·변경은 Flyway SQL 마이그레이션으로 관리한다.
- Flyway는 `spring-boot-starter-flyway`와 PostgreSQL 지원 모듈을 사용하고 버전은 Boot가 관리한다.
  마이그레이션 경로는 `classpath:db/migration`이다. 적용한 파일은 수정하지 않고 새 버전 파일을 추가한다.
- 기존 DB 상태 확인용 `JdbcClient`는 유지할 수 있지만, 계정 저장 로직에는 JDBC와 JPA를 혼용하지 않는다.

### V1에서 현재 계정 구조로 전환

`main`의 `V1__create_users_and_auth_sessions.sql`은 운영에 이미 적용됐으므로 그대로 보존한다.
현재 브랜치에서 따로 만들었던 `V1__create_auth_accounts.sql`은 제거하고,
`V2__align_auth_accounts.sql`로 [현재 ERD](../account/AUTH_ERD.md)의 구조를 만든다.

운영 테이블이 비어 있다는 확인을 바탕으로 V2는 다음 변경을 수행한다.

- 사용하지 않는 `auth_sessions`를 제거한다. 활성 세션과 RT 메타데이터는 Redis에 저장한다.
- `users`의 `google_subject`·`email`·`status`를 제거하고 표시 이름 길이를 50자로 맞춘다.
- 사용자 ID·생성 시각·수정 시각의 DB 기본값을 제거한다. 현재 애플리케이션이 값을 생성한다.
- `oauth_identities`와 복합 PK, 사용자 FK·인덱스를 생성한다.

기존 계정·세션 데이터를 옮기는 마이그레이션은 포함하지 않는다. 새 DB는 V1과 V2를
순서대로 실행하며, 기존 V1 DB는 V2만 실행한다. V1의 `uuidv7()` 때문에 PostgreSQL 18이
필요하고, 테스트는 `postgres:18.4-alpine`으로 통일했다. 운영 반영 여부는
[스키마 반영 현황](../../../docs/TABLE_AND_LOGIC.md#9-스키마-반영-현황)에 기록한다.

Spring 통합 테스트는 자동 마이그레이션을 활성화하고, 전환 전용 테스트는 Flyway를
직접 호출한다. 실행 방식과 검증 항목은 [테스트 계획](TEST_PLAN.md#마이그레이션-실행-방식)을 따른다.

### 엔티티 매핑

| 대상 | 매핑 |
|---|---|
| `users` | `UserEntity`, `UUID` 타입 `@Id`. 생성은 ERD의 UUID v7 규칙을 사용하고 `@GeneratedValue`는 사용하지 않음 |
| `oauth_identities` | `OAuthIdentityEntity`, `@EmbeddedId`로 복합 PK 매핑 |
| 복합 키 | `OAuthIdentityId`에 `provider`, `providerId`. 두 필드 기반 `equals`·`hashCode` 구현 |
| 사용자 참조 | `@ManyToOne(fetch = LAZY, optional = false)`와 `@JoinColumn(name = "user_id", nullable = false)` |
| 생성·수정 시각 | Java `Instant`. UTC `Clock`으로 값을 설정하고 PostgreSQL `timestamptz`에 저장 |

사용자에서 소셜 정보로 향하는 역방향 컬렉션은 두지 않는다. 필요한 정보는 Repository 조회로 가져온다.
연관관계의 자동 저장·삭제 전파와 orphan removal은 사용하지 않고 두 엔티티의 저장을 명시적으로 수행한다.
컬럼명·길이·NULL 허용 여부는 명시적으로 매핑하며, 실제 제약은 Flyway에서 ERD와 동일하게 생성한다.
`oauth_identities`의 복합 PK 제약 이름은 `pk_oauth_identities`로 고정한다.

### 생성과 수정

UUID와 복합 키를 저장 전에 할당하므로, 신규 생성은 `AccountTransactions`의 트랜잭션 메서드에서
`EntityManager.persist()`를 호출한다. ID가 채워져 있다는 이유로 기존 엔티티로 판단해
`merge()`하는 동작에 의존하지 않는다.

조회는 Spring Data Repository를 사용하고, 수정은 쓰기 트랜잭션에서 조회한 엔티티의
변경 감지를 사용한다. 부분 필드만 채운 엔티티를 만들어 `save()`하지 않는다.
본인 계정과 이메일 조회는 DTO projection 또는 필요한 연관관계의 명시적 조회로 처리한다.
표시 이름·이메일 갱신 범위와 시각 변경은 [계정 저장 규칙](../account/AUTH_ERD.md#2-초기-저장-규칙)을 따른다.

### 트랜잭션 Bean과 예외 변환

영속성 어댑터는 별도 Spring Bean의 트랜잭션 메서드를 호출한다.
이 Bean의 생성·수정 메서드에는 `@Transactional(rollbackFor = Exception.class)`,
읽기 메서드에는 `@Transactional(readOnly = true)`를 사용한다.
같은 객체 내부 호출에 의존하지 않으며, 코어의 애플리케이션 서비스에는 `@Transactional`을 붙이지 않는다.
격리 수준·커밋·반환 경계는 [영속성 설계](../account/PERSISTENCE_DESIGN.md#트랜잭션-경계)를 따른다.

신규 계정 저장 마지막에 `flush()`로 제약 위반을 확인한다.
롤백 완료 후 SQLSTATE `23505`와 `pk_oauth_identities` 제약 이름이 함께 일치할 때만
`PortFailure.Kind.IDENTITY_ALREADY_REGISTERED`로 변환한다. 다른 무결성 오류를 동일 계정 중복으로 취급하지 않는다.

### DTO·엔티티 변환과 설정 바인딩

- 웹 요청·응답은 Java record로 정의한다. Jackson으로 JSON 필드명을 매핑하고 Bean Validation으로
  필수 값과 콜백의 `code`·`error` 배타 조건을 검사한다. 표시 이름 규칙은 도메인에 남긴다.
- MapStruct의 `AuthRequestMapper`·`AuthResponseMapper`가 웹 DTO와 유스케이스 입력·결과를,
  `AccountEntityMapper`가 JPA 엔티티와 코어 모델을 변환한다. 누락된 대상 필드는 컴파일 오류로 처리한다.
- 단순 생성자 주입은 Lombok으로 생성한다. 코어의 컴파일 결과에 프레임워크 의존이 생기지 않는지는
  ArchUnit으로 검증한다. 초기화 로직이 있는 생성자는 직접 작성한다.
- `JwtProperties`·`GoogleProperties`는 `@ConfigurationProperties`로 설정을 바인딩하고
  필수 값을 검증한다. 기존 환경변수 이름, RSA 키 검증과 콜백 URL 제한은 유지한다.

### 공식 참고

- [Spring Data JPA 신규 엔티티 판단](https://docs.spring.io/spring-data/jpa/reference/jpa/entity-persistence.html)
- [Spring 트랜잭션 전파와 rollback-only](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html)
- [Spring Boot 데이터베이스 초기화](https://docs.spring.io/spring-boot/how-to/data-initialization.html)

## 사용자 UUID 생성

Java 21에서는 `com.fasterxml.uuid:java-uuid-generator:5.2.0`을 사용한다.
`Generators.timeBasedEpochRandomGenerator(new SecureRandom())`를 재사용하는 생성기로 두고,
계정 생성 시 `generate()`로 UUID v7을 만든다. DB에는 문자열이 아닌 `uuid`로 바인딩한다.
같은 밀리초의 생성 순서나 여러 인스턴스 사이의 전역 순서는 보장한다고 가정하지 않는다.

라이브러리의 UUID v7 생성 API와 Java 지원 범위는
[JUG 공식 문서](https://github.com/cowtowncoder/java-uuid-generator)와
[5.2.0 릴리스 기록](https://github.com/cowtowncoder/java-uuid-generator/blob/java-uuid-generator-5.2.0/release-notes/VERSION)에서 확인했다.

## JWT 키 설정

로컬 개발에서는 Auth 개인키를 환경변수로 주입한다.
키는 RSA 2048비트로 생성한다. `AUTH_JWT_PRIVATE_KEY_BASE64`에는 암호화되지 않은 PKCS#8 DER의
표준 Base64 문자열을 한 줄로 넣는다. Base64는 전달 인코딩이며 암호화가 아니다.
`AUTH_JWT_KEY_ID`는 키 생성 시 함께 만든 UUID v4 문자열로 고정하고, 재시작 때 새로 만들지 않는다.

Auth와 BFF는 `AUTH_JWT_KEY_ID`와 `AUTH_JWT_PUBLIC_KEY_PATH`로 검증 키를 설정한다.
공개키 파일은 X.509 SubjectPublicKeyInfo PEM(`BEGIN PUBLIC KEY`) 형식이다.
키 로딩·교체 정책은 [JWT 설계](../token/JWT_DESIGN.md#공개키-전달)를 따른다.

## OAuth 요청 설정

| 항목 | 확정값 |
|---|---|
| 흐름 | Authorization Code, `response_type=code`, `response_mode=query` |
| 권한 범위 | `openid email profile` |
| PKCE | `S256`, `plain`으로 대체하지 않음 |
| Client ID·Secret | `AUTH_GOOGLE_CLIENT_ID`, `AUTH_GOOGLE_CLIENT_SECRET` 환경변수 |
| 토큰 교환의 클라이언트 인증 | `client_secret_basic` |
| 콜백 설정 | `AUTH_GOOGLE_REDIRECT_URI`. 운영 URL은 `https://api.loresentry.com/auth/oauth/google/callback` |
| Google HTTP 호출 | 연결 제한 1초, 개별 요청 제한 3초, 콜백 내 Google 통신 전체 제한 8초 |

로컬 콜백 URL은 실제 브라우저에서 접근하는 BFF 주소로 설정하고 Google에도 동일하게 등록한다.
HTTP 콜백은 로컬 프로필의 `localhost`·루프백 주소에만 허용한다.
Google Client ID·Secret·리다이렉트 URI가 없거나 URI가 허용 규칙에 맞지 않으면 시작 시 실패한다.
코드 교환 실패와 Google 토큰 사용 범위는 [OAuth 설계](../login/OAUTH_STATE.md#google-요청-설정)를 따른다.

Auth의 `GoogleSettings`와 [BFF 로그인 계약](../../../loresentry-gateway/docs/auth/LOGIN_FLOW.md)은
`/auth/oauth/google/callback`으로 통일했다. 배포 담당자는 Google 등록 URL과
`AUTH_GOOGLE_REDIRECT_URI`를 위 운영 URL로 설정하고 실제 BFF 연동을 검증해야 한다.

## 활성 세션 저장과 명령 경계

`SessionStore.Session`은 UUID v4 sid·refreshJti와 초 정밀도의 미래 만료용 값을 표현한다.
실제 미래 여부는 Lua가 `TIME`으로 검사한다. `RedisSessionStore`는 검증된 UUID·정수를
네 필드의 UTF-8 JSON으로 직렬화하고 `auth:session:{userId}` 하나만 변경한다.
Lua는 미지원 버전·필드 누락·잘못된 형식·Redis 자료형 오류를 `INVALID_DATA`로 반환한다.

어댑터는 가상 스레드에서 연결 획득부터 명령 실행까지 500ms로 제한한다.
연결 획득 중 취소가 먼저 확정되면 이후 연결이 돌아와도 EVAL을 호출하지 않아 `NOT_EXECUTED`다.
실행에 진입한 뒤 응답을 확인하지 못하면 `UNKNOWN`이다. 새로 연결해 같은 명령을 자동 재전송하지 않는다.
Lettuce는 끊어진 연결의 명령을 거절하고 재연결 시 기존 명령 replay를 막는다.

세션 명령은 `EVAL`, 스크립트 내부 `TIME`·`GET`·`SET`·`DEL`이다.
OAuth의 `SET NX`·`GET`·`GETDEL`은 별도 포트에 남는다. 운영 ACL과 클라이언트 초기화 명령은
[전환 인계](../../../docs/auth/implementation/SESSION_HANDOFF.md)에서 확인한다. 테스트 실행 기록은 [테스트 계획](TEST_PLAN.md#실행-결과)을 따른다.

## OAuth 상태 직렬화

`login_request_id`, `state`, `nonce`, `code_verifier`는 요청마다 각각 독립적인
`SecureRandom` 32바이트를 padding 없는 Base64URL로 인코딩해 생성한다.
`code_challenge`는 `BASE64URL(SHA256(code_verifier))`로 계산한다.
Spring Security의 nonce 검증 방식에 맞춰 Google에는 `BASE64URL(SHA256(ASCII(nonce)))`를 보내고,
Auth에는 원본 `nonce`를 저장한다. ID Token의 nonce도 이 해시 값과 일치해야 한다.

Redis 키는 `auth:oauth:{login_request_id}`이며, `SET NX EX 300`으로 생성한다.
키 충돌 시 새 식별자를 만들어 다시 저장하고 기존 요청을 덮어쓰지 않는다.
값은 아래 필드만 가진 DTO를 UTF-8 JSON으로 직렬화한다.

| 필드 | 내용 |
|---|---|
| `schema_version` | 정수 `1` |
| `registration_id` | `google` |
| `client_id` | 해당 요청을 만든 Google Client ID |
| `redirect_uri` | 해당 요청에 사용한 고정 콜백 URI |
| `state` | 콜백과 비교할 요청별 값 |
| `nonce` | Spring Security 요청 속성에 복원할 원본 값. Google에 보낸 해시 값을 검증하는 데 사용 |
| `code_verifier` | Auth에서 토큰 교환에 사용할 PKCE 값 |
| `created_at` | 생성 시각, UTC epoch 초 |
| `expires_at` | 생성 시각 + 300초, UTC epoch 초 |

Redis 키·값 전송은 `StringRedisTemplate`, JSON 변환은 Spring Boot가 관리하는 Jackson을 사용한다.
Java 객체 직렬화나 클래스명을 포함하는 다형 역직렬화는 사용하지 않는다.
필수 필드 누락·지원하지 않는 버전·형식 오류는 거절한다. DTO 원문과 비밀 값은 로그에 남기지 않는다.
Spring Security의 요청 객체는 이 DTO와 서버 설정으로 재구성한다.
`nonce`와 `code_verifier`는 요청 속성에, nonce 해시와 PKCE challenge는 요청 파라미터에 복원한다.
임의의 요청 속성 전체를 저장하지 않는다.

### 공식 참고

- [Google OIDC 설정](https://accounts.google.com/.well-known/openid-configuration): S256·scope·클라이언트 인증 방식.
- [OAuth 보안 권고](https://www.rfc-editor.org/rfc/rfc9700.html#section-2.1.1): 서버형 클라이언트에도 PKCE 권고.
- [Spring Data Redis 직렬화](https://docs.spring.io/spring-data/redis/reference/redis/template.html): JSON과 Java 직렬화의 차이.
- [Spring Security nonce 검증](https://github.com/spring-projects/spring-security/blob/main/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/oidc/authentication/OidcAuthorizationCodeAuthenticationProvider.java): 원본 nonce 속성을 복원해야 해시 검증이 수행됨.

## HS256과 RS256의 키 구조

두 방식 모두 SHA-256을 사용하지만, 토큰을 생성하고 검증할 때 사용하는 키 구조가 다르다.

| 구분 | 기존 HS256 | 채택한 RS256 |
|---|---|---|
| 방식 | HMAC + SHA-256 | RSA 서명 + SHA-256 |
| Auth의 토큰 생성 | 공유 비밀키 사용 | 개인키 사용 |
| BFF의 AT 검증 | 동일한 공유 비밀키 사용 | 공개키 사용 |
| BFF의 검증 키로 유효한 서명 생성 | 가능 | 불가능 |

HS256은 검증 서버에도 토큰을 만들 수 있는 비밀키를 공유해야 한다.
RS256을 선택하면 Auth만 개인키를 보유하고 BFF에는 공개키만 전달해,
토큰 발급과 검증의 키 권한을 분리할 수 있다.
두 방식은 JWT의 위조·변조 여부를 검증하는 용도이며, 토큰 내용을 암호화하지 않는다.
