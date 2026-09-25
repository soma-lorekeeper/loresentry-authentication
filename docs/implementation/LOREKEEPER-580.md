# LOREKEEPER-580 검증 기록

단일 세션 ID 생성·정규 형식·해시와 schema v2 로그인 인덱스 생성·교체를 구현했다.
기준은 `docs/session/SESSION_DESIGN.md`이며 API 연결은 후속 LOREKEEPER-581에서 수행한다.

- Java 21 컨테이너에서 SessionIdTest 2개, LoginSessionStoreTest 5개, ArchitectureTest 2개가 통과했다.
- Redis 7.4-alpine과 Valkey 9.0.6-alpine에서 각각 같은 9개 테스트가 통과했다.
- Spotless 검사와 `git diff --check`가 통과했다.
- 정규 인코딩·고정 SHA-256 값·원문 비저장, 같은 절대 만료·14일 TTL, 충돌 시 무변경을 확인했다.
- 독립 Redis 연결의 동시 로그인, 잘못된 스키마·중복/이스케이프 필드·TTL 없음·잘못된 자료형을 검증했다.
- 첫 SET 뒤 오류를 주입해 일부 쓰기가 남아도 성공을 반환하지 않고 현재 사용자 인덱스가 유지되는지 확인했다.
- EVAL 응답 유실은 결과 불명이며 재전송하지 않음을 확인했다.

재현: Docker 소켓을 사용할 수 있는 Java 21 환경에서 아래 명령을 실행한다.

```bash
./gradlew --no-daemon --max-workers=2 spotlessCheck test --tests '*SessionIdTest' --tests '*LoginSessionStoreTest' --tests '*ArchitectureTest'
AUTH_TEST_REDIS_IMAGE=valkey/valkey:9.0.6-alpine ./gradlew --no-daemon --max-workers=2 test --tests '*SessionIdTest' --tests '*LoginSessionStoreTest' --tests '*ArchitectureTest'
```

이번 검증은 로그인 저장 경계에 한정한다. BFF 활동 연장·HTTP 콜백·폐기와 운영 환경 검증은 후속 Atomic에서 수행한다.
