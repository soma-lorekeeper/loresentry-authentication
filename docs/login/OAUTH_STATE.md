# OAuth 임시 상태와 검증

Google 로그인마다 Redis에 상태를 5분간 저장하고, 브라우저 연결과 state를 검증한 뒤 한 번 소비한다.
전체 흐름은 [로그인 설계](LOGIN_FLOW.md), 전달 필드는 [내부 API](../INTERNAL_API.md)를 따른다.

## Google 요청 설정

Authorization Code와 OIDC를 사용하며 PKCE S256·nonce를 검증한다.
Google 토큰은 로그인 검증 중에만 사용하고 장기 저장·offline 접근은 하지 않는다.
코드 교환은 자동 재시도하지 않으며, 결과가 불명확하면 로그인을 다시 시작한다.
고정 콜백 URI, 환경변수, scope와 통신 제한은 [요청 설정](../implementation/IMPLEMENTATION_NOTES.md#oauth-요청-설정)을 따른다.

## 상태 저장

Redis 키는 `auth:oauth:{login_request_id}`이며, 기존 요청을 덮어쓰지 않고 TTL 300초로 생성한다.
식별자·state·nonce·PKCE 값의 생성과 JSON 저장 형식은 [직렬화 계약](../implementation/IMPLEMENTATION_NOTES.md#oauth-상태-직렬화)을 따른다.
상태 원문과 비밀 값은 로그에 남기지 않으며, 임시 상태 만료에는 JWT의 시계 오차 허용을 적용하지 않는다.

## 콜백 검증과 소비

1. 임시 쿠키에서 받은 `login_request_id`로 조회하고 `state`, 만료, 제공자·Client ID·콜백 URI를 확인한다.
   요청별 문자열은 원문 그대로 비교하며, 불일치하면 상태를 소비하지 않는다.
2. 확인된 키를 `GETDEL`로 소비한다. 반환된 상태에도 같은 검증을 적용하며, 이미 없으면 거절한다.
   앞의 조회가 여러 요청에서 성공하더라도 소비는 한 요청만 성공할 수 있다.
3. 성공 콜백은 저장한 `code_verifier`로 코드를 교환한다. Google ID Token의 서명·발급자·사용 대상·만료와
   `nonce`를 검증한 뒤에만 계정 정보를 사용한다. ID Token의 사용 대상은 Loresentry AT의 `aud`가 아니라 Google Client ID다.
4. 취소·거절 콜백도 브라우저 연결과 `state`를 확인한 뒤 소비한다. 소비 후 실패한 상태는 복구하지 않는다.

서명 검증과 OIDC 표준 검증은 Spring Security를 사용하고, 저장한 요청의 `nonce` 검증을 명시적으로 연결한다.
Google 공개키는 고정한 Google 제공자 설정에서 조회하며, 토큰이 지정한 임의 URL을 사용하지 않는다.


검증 항목은 [테스트 계획](../implementation/TEST_PLAN.md#oauth-상태)을 참고한다.
