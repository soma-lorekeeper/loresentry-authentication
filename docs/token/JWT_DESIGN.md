# Auth JWT 설계

2026-09-24 Auth 구현은 [단일 로그인 세션](SINGLE_SESSION_DESIGN.md)의 필수 `sid`를 포함한다.
운영 전환과 BFF 검증 구현은 후속 작업이다.

**AT·RT 서명 알고리즘은 RS256이다.**

## 서명과 키 관리

Auth는 개인키를 보관해 Access Token(AT)과 Refresh Token(RT)에 서명하고,
검증용 공개키를 제공한다. BFF의 검증 처리는 [BFF 인증 책임 문서](../../../docs/bff/auth/AUTH_RESPONSIBILITIES.md#일반-보호-api)에서 관리한다.

### 공개키 전달

검증용 공개키는 **파일로 배포하고 BFF 설정으로 주입**한다.
로컬 개인키는 환경변수로 주입한다. RSA 키는 2048비트로 생성하고 `kid`는 재시작해도 유지한다.
환경변수·키 인코딩은 [구현 노트](../implementation/IMPLEMENTATION_NOTES.md#jwt-키-설정)를 따른다.
현재 `kid`에 등록된 키만 검증에 사용한다.
초기에는 시작 시 키를 로딩하고, 설정 변경은 재시작으로 적용한다.

키 누락·형식 오류·2048비트 미만 RSA 키는 시작 시 실패시킨다.
Auth는 개인키와 공개키가 한 쌍인지도 확인한다. 키를 자동 생성하거나 검증을 생략해 기동하지 않는다.

### 기존 토큰을 거절하는 키 교체

**서명키 교체가 완료되면 이전 키로 발급한 AT·RT를 모두 거절하고 재로그인을 요구한다.**
기존 토큰의 만료까지 이전 공개키를 유지하는 유예 기간은 두지 않는다.
AT·RT의 JOSE 헤더에 서명키 식별자 `kid`를 넣고, 새 키에는 재사용하지 않는 새 식별자를 부여한다.

1. 새 키 쌍과 새 `kid`를 준비한다.
2. Auth의 발급 개인키·`kid`와 Auth·BFF의 검증 공개키를 새 키로 교체한다.
   이전 개인키와 공개키는 런타임 설정에서 제거한다.
3. 모든 Auth·BFF 인스턴스가 새 설정을 로딩하고 이전 키의 토큰을 거절하는지 확인한 뒤 교체 완료로 판단한다.

여러 인스턴스의 설정이 혼재하는 동안에는 인증 결과가 달라질 수 있으므로,
교체 중 트래픽 제어와 재시작 순서는 인프라와 협의한다. 무중단 교체를 보장하지 않는다.
교체 후 이전 RT의 Redis 상태가 남아 있어도 JWT 검증에서 거절되므로 재발급할 수 없다.
해당 상태는 기존 TTL로 정리하며, 키 교체를 위한 Redis 전체 삭제는 하지 않는다.

## 토큰 유효기간

| 토큰 | 확정 유효기간 |
|---|---|
| Access Token(AT) | 15분 |
| Refresh Token(RT) | 14일 |

RT 재발급 시 만료 연장 정책은 [RT 설계](REFRESH_TOKEN_DESIGN.md#만료-연장)에서 관리한다.

## JWT 필드와 검증

AT·RT의 JOSE 헤더에는 `alg=RS256`과 필수 `kid`를 넣는다.
`kid`가 없거나 등록되지 않은 값이면 거절하며, 토큰에 담긴 URL이나 경로로 키를 찾지 않는다.
`kid`는 검증 키를 선택하는 용도이며, 키를 선택한 뒤 서명을 검증해야 한다.

페이로드는 다음 필드와 값을 사용한다.

| 필드 | 내용 |
|---|---|
| `sub` | 내부 사용자 UUID의 문자열 표현 |
| `iss` | AT·RT 모두 `loresentry-auth` |
| `aud` | AT는 `loresentry-api`, RT는 `loresentry-auth` |
| `iat` | 발급 시각 |
| `exp` | 만료 시각 |
| `jti` | AT·RT 각각 `UUID.randomUUID()`로 생성한 UUID v4 문자열 |
| `sid` | 로그인 시 생성한 UUID v4 문자열. AT·RT가 공유하고 재발급 중 유지 |
| `token_type` | AT는 `access`, RT는 `refresh` |

위 필드는 모두 필수이며, `sub`·`jti`의 UUID 형식과 `sid`의 UUID v4 형식을 확인한다.
`sid` 없는 이전 AT·RT는 승계하지 않고 재로그인한다.
서명·만료·발급자·사용 대상과 토큰 종류를 검증한다.
AT·RT 모두 `iss=loresentry-auth`와 정확히 일치하는지 확인한다.
AT 검증 경로는 `aud`에 `loresentry-api`가 포함되고 `token_type=access`인 토큰만 허용한다.
RT 검증 경로는 `aud`에 `loresentry-auth`가 포함되고 `token_type=refresh`인 토큰만 허용한다.
Auth의 RT 검증은 서명 알고리즘을 RS256으로 고정하며,
서버에 저장된 RT 상태도 [RT 설계](REFRESH_TOKEN_DESIGN.md)에 따라 확인한다.

시각은 UTC `Instant`로 처리하고 JWT 시각 필드는 초 단위 NumericDate로 직렬화한다.
Auth와 BFF의 JWT 시계 오차 허용값은 30초로 통일한다. `iat`가 현재보다 30초 넘게 미래이거나
`exp <= iat`이면 거절한다. Redis TTL은 기존대로 `exp`까지 설정하며 오차 허용 시간만큼 늘리지 않는다.
따라서 RT는 JWT의 시간 검증을 통과해도 Redis 상태가 만료됐다면 거절된다.

## 배포 시 협의 사항

배포 환경의 개인키 보관, 개인키·공개키 주입 수단과 인스턴스별 반영 확인은 인프라와 협의한다.

검증 항목은 [테스트 계획](../implementation/TEST_PLAN.md#jwt), 키 구조 비교는 [구현 노트](../implementation/IMPLEMENTATION_NOTES.md#hs256과-rs256의-키-구조)를 참고한다.
