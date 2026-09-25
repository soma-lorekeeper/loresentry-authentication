# Auth 문서

**2026-09-26부터 서비스 로그인 설계는 단일 세션 ID를 기준으로 한다.** 마지막 인증 활동
후 14일에 만료하고 활동 시 연장한다. 이번 변경은 문서에만 반영했으며 현재 코드·설정·
테스트·운영은 새 계약으로 전환하지 않았다. 기존 테스트 통과 수치는 새 설계의 검증 근거가 아니다.

## 읽는 순서

| 문서 | 내용 |
|---|---|
| [세션 계약](session/SESSION_DESIGN.md) | ID 생성·해시, 활성 세션, 활동 만료 연장, 폐기와 동시성 |
| [Auth 책임](../../docs/auth/AUTH_RESPONSIBILITIES.md) | BFF와의 역할 구분 |
| [애플리케이션 구조](ARCHITECTURE.md) | 코어·포트·어댑터의 목표 구조 |
| [내부 API](INTERNAL_API.md) | 로그인 결과와 세션 폐기·계정 API |
| [로그인](login/README.md) | Google OAuth 준비·콜백과 계정 처리 |
| [계정](account/README.md) | 사용자·소셜 정보와 영속성 |
| [구현 참고](implementation/README.md) | 구현할 계약과 테스트 계획 |
| [코드 읽기](code-guide.md) | 현재 코드에서 유지할 계정·OAuth 경계 |
| [공동 전환 인계](../../docs/auth/implementation/SESSION_HANDOFF.md) | Auth·BFF·프론트·운영 전환 조건 |

## 구현·검증 상태

세션 ID 방식의 발급·검증/연장·폐기와 쿠키·API 전환은 미구현이다.
실제 Google·브라우저·운영 ACL·접근 제한 검증도 새 계약으로 수행해야 한다.
이전 실행 이력은 [과거 검증](verification-2026-09-24.md)과
[기존 코드 테스트 기록](../TEST_COVERAGE.md)에 보존한다.

BFF는 [BFF 문서](../../loresentry-gateway/docs/README.md), 프로젝트 공통 맥락은
[공통 문서](../../docs/README.md)를 참고한다.
