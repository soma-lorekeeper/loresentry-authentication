# Auth 애플리케이션 구조

**하나의 Gradle 모듈에서 헥사고날 구조를 사용한다.** 코어는 인증·계정 규칙과 처리 순서를,
어댑터는 HTTP·저장소·외부 연동을 담당한다.

## 패키지와 의존 방향

기본 패키지 아래에 다음 경계를 두고, 필요하면 내부를 `account`, `login`, `token`으로 나눈다.

| 패키지 | 역할·의존 규칙 |
|---|---|
| `domain` | 계정 모델·규칙. Java 표준 라이브러리만 사용 |
| `application.port.in` | 로그인 준비·콜백, 재발급·폐기, 계정 조회·수정의 입력·결과 계약 |
| `application.port.out` | 계정 저장, OAuth 상태, 소셜 신원 검증, 사용자 세션, JWT, 사용자 ID 생성 계약 |
| `application.service` | 유스케이스 구현. domain과 포트에만 의존 |
| `adapter.in.web` | 입력 포트 호출, HTTP DTO·오류 매핑 |
| `adapter.out` | 출력 포트 구현. persistence·redis·google·jwt·id 패키지로 구분 |
| `config` | 구현체 선택과 생성자 주입, `config.properties`의 설정 바인딩. 코어 서비스는 Spring 컴포넌트 어노테이션 없이 `@Bean`으로 등록 |

코어는 어댑터나 Spring·JPA·Redis·HTTP 타입을 참조하지 않는다.
인터페이스는 유스케이스와 외부 경계에 두고 내부 도우미마다 만들지 않는다.
시간은 Java `Clock`을 직접 주입하며 별도 시간 포트를 만들지 않는다.

## 포트와 데이터 경계

계정 저장은 JPA·Hibernate, OAuth 임시 상태·사용자 세션은 Redis, Google 신원 검증은 Spring Security OAuth2/OIDC,
JWT는 서명 라이브러리·RSA 키, 사용자 ID는 [UUID 생성기](implementation/IMPLEMENTATION_NOTES.md#사용자-uuid-생성)로 구현한다.

소셜 신원 포트는 Google 토큰 검증 후 provider·subject·이름·이메일만 반환한다.
도메인의 `User`·`OAuthIdentity`와 JPA의 `UserEntity`·`OAuthIdentityEntity`를 분리하고,
영속성 어댑터에서 변환한다. Spring Security 객체·제공자 DTO·JPA 엔티티는 코어로 전달하지 않는다.
웹 어댑터는 유스케이스 결과를 HTTP DTO로 변환한다.
현재 변환은 웹·영속성 어댑터의 MapStruct 매퍼가 담당하며, JSON·Bean Validation은 웹 DTO에 둔다.
Lombok은 단순 생성자를 컴파일 시 생성하고, 코어의 컴파일 결과에 프레임워크 의존이 없는지
ArchUnit으로 확인한다. 구체적인 클래스와 설정은 [구현 노트](implementation/IMPLEMENTATION_NOTES.md#dto엔티티-변환과-설정-바인딩)를 참고한다.

표시 이름·이메일 변경 규칙은 코어가 판단하고, 저장 포트에는 변경할 필드를 명시한다.
전체 엔티티 덮어쓰기로 다른 필드의 변경을 지우지 않는다.

## 처리 순서와 트랜잭션

애플리케이션 서비스가 [로그인](login/LOGIN_FLOW.md)·[OAuth 상태](login/OAUTH_STATE.md)·[RT](token/REFRESH_TOKEN_DESIGN.md)의 처리를 조정한다.
DB 작업과 커밋 시점은 [영속성 설계](account/PERSISTENCE_DESIGN.md#트랜잭션-경계)를 따른다.

`SessionStore`는 로그인 `replace`, 조건부 갱신 `rotate`, sid 조건부 폐기 `revoke`를 제공한다.
`RedisSessionStore`는 단일 사용자 키의 Lua 스크립트로 조건 확인과 최종 변경을 수행한다.
`rotate=false`는 부재·만료·조건 불일치이며, 장애와 손상된 데이터는 `PortFailure`로 구분한다.
명령 미실행과 실행 결과 미확인도 구분한다. OAuth의 일회성 소비는 기존 포트를 유지한다.
어댑터가 SQL·Redis 등의 기술 예외를 포트 계약으로 변환하고, 웹 어댑터가 [API 오류](INTERNAL_API.md#오류-계약)로 매핑한다.
계층별 예외 처리와 응답 변환은 [구현 노트](implementation/IMPLEMENTATION_NOTES.md#오류-처리)를 따른다.
검증 방법은 [테스트 계획](implementation/TEST_PLAN.md#구조와-테스트-경계)을 따른다.
