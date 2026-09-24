# 구현 참고와 검증

현재 Auth 구현의 라이브러리·설정, 마이그레이션과 검증 항목을 관리한다.

| 문서 | 내용 |
|---|---|
| [구현 노트](IMPLEMENTATION_NOTES.md) | 오류 처리, JPA·MapStruct 매핑, Flyway V1→V2, 설정 바인딩, JWT·OAuth 설정 |
| [세션 연동·전환 인계](../../../docs/auth/implementation/SESSION_HANDOFF.md) | BFF 읽기 계약, ACL 명령, 배포·롤백 전제조건 |
| [테스트 계획](TEST_PLAN.md) | 실행 결과와 구조·계정·마이그레이션·OAuth·JWT·RT 검증 항목 |

작업할 기능의 설계를 읽은 뒤 해당 구현 노트와 테스트 항목을 확인한다.
전체 안내와 기능별 설계는 [Auth README](../README.md)를 참고한다.
