# 계정 설계

사용자·소셜 로그인 정보의 구조와 DB 저장 계약을 관리한다.

| 문서 | 내용 |
|---|---|
| [계정 ERD](AUTH_ERD.md) | 테이블·식별자·표시 이름·이메일 저장 규칙 |
| [계정 영속성](PERSISTENCE_DESIGN.md) | 트랜잭션 경계와 동시 가입 충돌 처리 |

ERD를 먼저 읽고 영속성 설계를 확인한다. JPA 매핑과 UUID 생성기는 [구현 참고](../implementation/README.md)를 따른다.
전체 안내는 [Auth README](../README.md)를 참고한다.
