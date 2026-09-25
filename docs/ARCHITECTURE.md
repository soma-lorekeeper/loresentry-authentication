# Auth 애플리케이션 구조

2026-09-26 목표 구조다. 현재 코드가 이 구조로 전환됐다는 의미는 아니다.
하나의 Gradle 모듈에서 헥사고날 구조를 유지한다. 코어는 인증·계정 규칙과 처리 순서를,
어댑터는 HTTP·저장소·외부 연동을 담당한다.

## 패키지와 의존 방향

| 패키지 | 역할 |
|---|---|
| `domain` | 계정 모델·규칙. Java 표준 라이브러리만 사용 |
| `application.port.in` | 로그인 준비·콜백, 세션 폐기, 계정 조회·수정 |
| `application.port.out` | 계정 저장, OAuth 상태, 소셜 신원 검증, 세션 저장, 안전한 세션 ID 생성 |
| `application.service` | 유스케이스 조율. domain과 포트에만 의존 |
| `adapter.in.web` | HTTP DTO·검증·응답·오류 매핑 |
| `adapter.out` | persistence·redis·google·id 등의 외부 경계 구현 |
| `config` | 설정 바인딩·검증과 구현체 연결 |

코어는 Spring·JPA·Redis·HTTP 타입을 참조하지 않는다. 인터페이스는 유스케이스와
외부 경계에 두며 시간은 `Clock`을 주입한다. Redis 세션 만료의 기준 시각은 저장소
스크립트의 서버 시각을 사용한다. 패키지를 세분화하면 account·login·session으로 나눈다.

## 데이터와 트랜잭션

계정은 JPA·Hibernate, OAuth 임시 상태와 세션은 Redis, Google 신원 검증은
Spring Security OAuth2/OIDC를 사용한다. 소셜 신원 포트는 검증한 제공자·subject·이름·
이메일만 반환한다. 제공자 DTO와 JPA 엔티티는 코어로 전달하지 않는다.

웹·영속성 어댑터의 MapStruct 변환과 웹 DTO의 JSON·Bean Validation 경계를 유지한다.
Lombok은 단순 생성자 생성에 사용하며 ArchUnit으로 코어의 의존 규칙을 검증한다.

[로그인](login/LOGIN_FLOW.md)에서는 Google 검증 뒤 짧은 계정 트랜잭션을 커밋하고,
그 이후 세션을 생성한다. 전체 로그인을 DB 트랜잭션으로 감싸지 않는다.
[영속성 설계](account/PERSISTENCE_DESIGN.md)의 동시 가입 처리와 부분 필드 수정 규칙을 유지한다.

## 세션 포트

Auth는 안전한 ID 생성과 세션 생성·교체·조건부 폐기를 담당한다.
BFF의 검증·활동 연장은 [공유 세션 계약](session/SESSION_DESIGN.md)의 별도 저장소 연산이다.
Auth와 BFF가 같은 JSON·키·Lua 계약을 사용하도록 테스트로 확인한다.
세션 원문은 요청 처리 중에만 보유하고 Redis에는 해시를 저장한다.

원자적 연산을 서비스의 개별 find/save 호출로 분해하지 않는다. 부재·만료·조건 불일치,
저장소 장애·손상, 실행 결과 미확인을 포트 결과로 구분한다. OAuth 일회성 소비 포트는 유지한다.
어댑터가 기술 예외를 포트 실패로, 웹 어댑터가 [API 오류](INTERNAL_API.md#오류-계약)로 변환한다.
구체적인 클래스 이름은 구현 시 확정하며 [테스트 계획](implementation/TEST_PLAN.md)을 따른다.
