# Auth 애플리케이션 구조

> **책임:** 패키지·의존 방향·프레임워크 경계와 계층별 오류 전달 책임을 정한다.
>
> **확인할 때:** 로직을 어느 계층에 둘지, 포트와 어댑터를 어떻게 나눌지 결정할 때.
>
> **관련 기준:** 실제 클래스 탐색은 [코드 안내](code-guide.md), HTTP 매핑은 [API 계약](API.md)을 본다.

하나의 Gradle 모듈에서 헥사고날 구조를 유지한다. 코어는 인증·계정 규칙과 처리 순서를,
어댑터는 HTTP·저장소·외부 연동을 담당한다.

## 패키지와 의존 방향

| 패키지 | 역할 |
|---|---|
| `domain` | 계정 모델·규칙과 세션 ID 형식·해시. Java 표준 라이브러리만 사용 |
| `application.port.in` | 로그인 준비·콜백, 세션 폐기, 계정 조회·수정 |
| `application.port.out` | 계정 저장, OAuth 상태, 소셜 신원 검증, 세션 저장, 안전한 세션 ID 생성 |
| `application.service` | 유스케이스 조율. domain과 포트에만 의존 |
| `adapter.in.web` | HTTP DTO·검증·응답·오류 매핑 |
| `adapter.out` | persistence·redis·google·id 등의 외부 경계 구현 |
| `config` | 설정 바인딩·검증과 구현체 연결 |

코어는 Spring·JPA·Redis·HTTP 타입을 참조하지 않는다. 인터페이스는 유스케이스와
외부 경계에 두며 시간은 `Clock`을 주입한다. Redis 세션 만료의 기준 시각은 저장소
스크립트의 서버 시각을 사용한다.

## 데이터와 프레임워크 경계

계정은 JPA·Hibernate, OAuth 임시 상태와 세션은 Redis, Google 신원 검증은
Spring Security OAuth2/OIDC를 사용한다. 소셜 신원 포트는 검증한 제공자·subject·이름·
이메일만 반환하며 제공자 DTO와 JPA 엔티티를 코어에 노출하지 않는다.

웹 요청·응답은 Java record이며 Jackson으로 JSON 필드명을 매핑한다. Bean Validation으로
[API 입력 조건](API.md#입력-검증)을 검사하고, 표시 이름의 업무 검증은 도메인에 둔다.
`AuthRequestMapper`·`AuthResponseMapper`가 웹 DTO와 유스케이스 입력·결과를 변환한다.
응답 매퍼는 세션 결과를 펼치고 소비 상태를 `true`·`false`·`null`로 변환한다.

웹·영속성 어댑터는 MapStruct를 사용하며 누락된 대상 필드는 컴파일 오류로 처리한다.
엔티티 매핑과 트랜잭션은 [계정 저장 구현](account/AUTH_ERD.md#5-jpa와-flyway)을 따른다.
생성된 매퍼는 `compileJava` 실행 후 `build/generated/sources/annotationProcessor/java/main/`에서 확인한다.
단순 생성자 주입은 Lombok을 사용하고 초기화 로직이 있는 생성자는 직접 작성한다.
`lombok.config`는 코어 바이트코드의 의존 경계를 유지하도록 생성 어노테이션을 비활성화한다.
ArchUnit으로 코어의 컴파일 결과에 프레임워크 의존이 생기지 않는지 검증한다.

## 처리 순서와 포트

[로그인 흐름](login/LOGIN_FLOW.md)이 Google 검증·계정 저장·세션 생성의 순서를 소유한다.
계정의 트랜잭션 경계는 [계정 규칙](account/AUTH_ERD.md#트랜잭션-경계)을 따른다.
애플리케이션 서비스는 출력 포트의 결과로 후속 처리를 결정한다.

[세션 계약](session/SESSION_DESIGN.md)의 생성·검증/연장·폐기는 원자적 연산 단위로 포트를 정의한다.
원자적 연산을 서비스의 개별 find/save 호출로 분해하지 않는다. OAuth의 일회성 소비도
포트의 연산으로 유지한다. 부재·만료·조건 불일치, 저장소 장애·손상, 실행 결과 미확인을
구분해 반환한다. 타임아웃만으로 상위 계층이 명령의 미실행을 추측하지 않는다.

## 오류 전달 경계

| 위치 | 책임 |
|---|---|
| 도메인·애플리케이션 | 업무 실패를 Java 예외로 표현하며 HTTP 상태·Spring 타입을 포함하지 않음 |
| 출력 어댑터 | DB·Redis·Google의 기술 예외를 포트가 정의한 실패로 변환 |
| 애플리케이션 서비스 | 작업 단계·실행 결과·정해진 복구 정책으로 유스케이스의 실패를 결정 |
| 웹 어댑터 | 전달된 실패를 [API 오류 응답](API.md#오류-계약)으로 변환 |

업무·포트 예외는 Java `RuntimeException` 기반으로 정의한다. catch는 예외 변환,
정해진 복구·재시도 또는 처리 상태 보존이 필요한 경계에 둔다.
동시 가입 충돌의 예외 변환은 [계정 문서](account/AUTH_ERD.md#트랜잭션-bean과-예외-변환)를 따른다.

### 웹 오류 변환과 로그

`adapter.in.web`의 `@RestControllerAdvice`·`@ExceptionHandler`가 오류를 HTTP 응답으로 변환한다.
컨트롤러마다 `try-catch`를 반복하지 않는다. MVC 이전 필터의 오류는 별도 처리 지점에서
같은 응답 변환을 사용한다. 콜백의 소비 상태와 명령 실행 결과를 보존하고, 타임아웃만으로
실패 단계를 추측하지 않는다.

JSON 해석·요청 형식·입력 검증 오류는 정해진 API 오류로 변환한다.
모든 `IllegalArgumentException`을 입력 오류로 간주하지 않으며 미분류 예외는
[API 오류 계약](API.md#오류-계약)의 고정 응답으로 처리한다. `getMessage()`를 응답에 직접 넣지 않는다.
예상하지 못한 오류의 원인과 스택은 비밀값을 제외해 서버에 기록하고 계층마다 중복 기록하지 않는다.
Spring MVC의 예외 처리 범위는 [공식 문서](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-exceptionhandler.html)를 참고한다.
