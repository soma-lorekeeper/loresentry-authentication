# 구현 참고와 검증

2026-09-26 세션 설계에 맞춰 구현할 경계와 유지할 계정·OAuth 설정을 관리한다.

| 문서 | 내용 |
|---|---|
| [구현 노트](IMPLEMENTATION_NOTES.md) | 오류·계정 저장·OAuth와 새 세션 저장 경계 |
| [테스트 계획](TEST_PLAN.md) | ID·활동 연장·폐기·동시성·브라우저·ACL 검증 |
| [공동 전환 인계](../../../docs/auth/implementation/SESSION_HANDOFF.md) | 역할·코드·프론트·운영 전환 |

새 세션 구현은 [580](LOREKEEPER-580.md), [581](LOREKEEPER-581.md),
[582](LOREKEEPER-582.md), [589](LOREKEEPER-589.md)에서 검증했다.
과거 실행 이력은 별도 보존하며 브라우저·운영 완료 근거로 사용하지 않는다.
