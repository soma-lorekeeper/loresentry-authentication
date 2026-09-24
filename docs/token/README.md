# 토큰 설계

자체 JWT의 발급·검증과 Refresh Token 상태 관리를 다룬다.

**2026-09-24 변경 설계:** [단일 로그인 세션 설계](SINGLE_SESSION_DESIGN.md)를 먼저 읽는다.
Auth의 사용자별 세션·RT 통합 저장은 구현·검증했다. BFF의 활성 세션 조회는 후속 구현이다.
JWT·RT 문서도 같은 계약을 따르며 운영 전환 조건은 [인계 문서](../../../docs/auth/implementation/SESSION_HANDOFF.md)에서 확인한다.

| 문서 | 내용 |
|---|---|
| [단일 로그인 세션 설계](SINGLE_SESSION_DESIGN.md) | 사용자별 세션·RT 저장, 동시성·오류·권한·전환·검증 계획 |
| [JWT 설계](JWT_DESIGN.md) | 서명·키 교체·필드·유효기간 |
| [Refresh Token 설계](REFRESH_TOKEN_DESIGN.md) | 저장·재발급·폐기와 실패 정책 |

JWT 규격을 먼저 읽고 RT 상태 처리 흐름을 확인한다. 키 인코딩·주입 설정은 [구현 참고](../implementation/README.md)를 따른다.
전체 안내는 [Auth README](../README.md)를 참고한다.
