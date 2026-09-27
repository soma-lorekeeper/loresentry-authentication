# OAuth 임시 상태와 검증

> **책임:** OAuth 요청값 생성·Redis 임시 상태·직렬화·신원 검증·일회성 소비를 정한다.
>
> **확인할 때:** state·nonce·PKCE·임시 상태 생성·소비 오류를 다룰 때.
>
> **관련 기준:** 서비스 가입·세션 발급 순서는 [로그인 흐름](LOGIN_FLOW.md)을 본다.

Google 로그인마다 Redis에 상태를 5분간 저장하고, 브라우저 연결과 state를 검증한 뒤 한 번 소비한다.
전체 흐름은 [로그인 설계](LOGIN_FLOW.md), 전달 필드는 [Auth 제공 API](../API.md)를 따른다.

## Google 요청 설정

Google 주소·scope·클라이언트 인증·환경변수·콜백 URI·통신 제한과 실패 처리는
[Auth의 호출 API](../API_CALLS.md#google-요청-설정)를 따른다.
이 문서는 요청값과 임시 상태의 생성·저장·검증·일회성 소비를 정한다.

## 상태 저장

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
임시 상태 만료에는 시계 오차 유예를 추가하지 않는다.

## 콜백 검증과 소비

1. 임시 쿠키에서 받은 `login_request_id`로 조회하고 `state`, 만료, 제공자·Client ID·콜백 URI를 확인한다.
   요청별 문자열은 원문 그대로 비교하며, 불일치하면 상태를 소비하지 않는다.
2. 확인된 키를 `GETDEL`로 소비한다. 반환된 상태에도 같은 검증을 적용하며, 이미 없으면 거절한다.
   앞의 조회가 여러 요청에서 성공하더라도 소비는 한 요청만 성공할 수 있다.
3. 성공 콜백은 저장한 `code_verifier`로 코드를 교환한다. Google ID Token의 서명·발급자·사용 대상·만료와
   `nonce`를 검증한 뒤에만 계정 정보를 사용한다. ID Token의 사용 대상은 Google Client ID다.
4. 취소·거절 콜백도 브라우저 연결과 `state`를 확인한 뒤 소비한다. 소비 후 실패한 상태는 복구하지 않는다.

서명 검증과 OIDC 표준 검증은 Spring Security를 사용하고, 저장한 요청의 `nonce` 검증을 명시적으로 연결한다.
Google 공개키는 고정한 Google 제공자 설정에서 조회하며, 토큰이 지정한 임의 URL을 사용하지 않는다.

검증 항목은 [테스트 계획](../implementation/TEST_PLAN.md#oauth-상태)을 참고한다.

### 공식 참고

- [Google OIDC 설정](https://accounts.google.com/.well-known/openid-configuration): S256·scope·클라이언트 인증 방식.
- [OAuth 보안 권고](https://www.rfc-editor.org/rfc/rfc9700.html#section-2.1.1): 서버형 클라이언트에도 PKCE 권고.
- [Spring Data Redis 직렬화](https://docs.spring.io/spring-data/redis/reference/redis/template.html): JSON과 Java 직렬화의 차이.
- [Spring Security nonce 검증](https://github.com/spring-projects/spring-security/blob/main/oauth2/oauth2-client/src/main/java/org/springframework/security/oauth2/client/oidc/authentication/OidcAuthorizationCodeAuthenticationProvider.java): 원본 nonce 속성을 복원해야 해시 검증이 수행됨.
