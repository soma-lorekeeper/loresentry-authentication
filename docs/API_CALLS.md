# Auth가 호출하는 API

> **책임:** Auth가 Google에 요청을 만들고 응답을 소비하는 방법과 통신 제한을 정한다.
>
> **호출자·대상:** Auth가 Google의 토큰·공개키 API를 호출한다. Google 인가 화면은 브라우저가 연다.
>
> **확인할 때:** Google 연동 주소·요청 설정·코드 교환·공개키 조회·통신 실패를 다룰 때.
>
> **관련 기준:** Auth가 제공하는 요청·응답은 [API.md](API.md), 임시 상태와 신원 검증은 [OAuth 상태](login/OAUTH_STATE.md)를 본다.

Google 연동은 현재 구현을 기준으로 한다.

## 호출 목록

| 작업 | 대상 | 호출 시점과 결과 사용 |
|---|---|---|
| Google 인가 화면 이동 | `https://accounts.google.com/o/oauth2/v2/auth` | Auth가 인가 URL을 구성해 BFF에 반환한다. BFF의 리다이렉트로 브라우저가 접근한다. Auth의 직접 HTTP 호출이 아니다. |
| 인증 코드 교환 | `POST https://oauth2.googleapis.com/token` | 소비한 OAuth 상태와 callback의 code로 토큰을 요청한다. ID Token을 검증한 뒤 계정 식별정보를 사용한다. |
| 서명 공개키 조회 | `GET https://www.googleapis.com/oauth2/v3/certs` | Spring Security의 ID Token 검증에 필요한 Google 공개키를 얻는다. |

주소와 호출 구현은 [GoogleOidcClient](../src/main/java/com/loresentry/authentication/adapter/out/google/GoogleOidcClient.java)에 있다.
Google API 전체 명세를 복제하지 않고 이 서비스가 사용하는 호출과 설정을 관리한다.

## Google 요청 설정

Authorization Code와 OIDC를 사용한다. PKCE·nonce의 생성·저장·검증은
[OAuth 상태](login/OAUTH_STATE.md)에서 정한다.
Google 토큰은 로그인 검증 중에만 사용하고 장기 저장·offline 접근은 하지 않는다.
코드 교환은 자동 재시도하지 않으며, 결과가 불명확하면 로그인을 다시 시작한다.

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

Auth의 `GoogleSettings`와 [BFF 로그인 계약](../../loresentry-gateway/docs/auth/LOGIN_FLOW.md)은
`/auth/oauth/google/callback`으로 통일했다. 배포 담당자는 Google 등록 URL과
`AUTH_GOOGLE_REDIRECT_URI`를 위 운영 URL로 설정하고 실제 BFF 연동을 검증해야 한다.
`GoogleProperties`로 설정을 바인딩하고 시작 시 필수 값과 콜백 URI를 검증한다.

## 응답 사용과 실패 처리

인증 코드 교환과 ID Token 검증은 Spring Security OAuth2/OIDC에 맡긴다. 검증된 제공자·
Google 계정 식별자·이름·이메일만 계정 처리에 전달한다. 토큰과 제공자 응답 원문을 BFF에 반환하지 않는다.
공개키 주소는 서버의 고정 Google 설정을 사용하며 토큰이 지정한 임의 URL을 사용하지 않는다.

소비한 OAuth 상태는 Google 오류나 시간 초과가 발생해도 복구하지 않는다.
신원 검증 실패와 통신 실패를 구분해 전달하고, HTTP 상태·오류 응답은
[제공 API의 오류 계약](API.md#오류-계약)을 따른다. 요청·토큰·Client Secret 원문은 로그에 남기지 않는다.
