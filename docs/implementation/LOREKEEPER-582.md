# LOREKEEPER-582 검증 기록

`POST /auth/sessions/revoke`는 입력 ID의 현재 해시를 재검증한 뒤 해당 로그인만 폐기한다.
이전 ID와 알 수 없는 정규 ID는 다른 로그인을 삭제하지 않는다. 중복 폐기는 204다.
형식 오류는 `INVALID_SESSION_ID`, 손상·저장소 오류·결과 불명은 `REVOCATION_UNCONFIRMED`다.

- 폐기 포트는 연결·사전 GET·Lua를 합해 500ms로 제한한다. GET이나 연결이 늦게 끝나도 제한 시간 뒤 삭제를 시작하지 않는다.
- 폐기는 같은 ID로 최대 3회 시도하고 100/200ms 대기와 시도 예산을 포함해 2초를 넘길 다음 시도를 시작하지 않는다.
- Lua는 두 인덱스·UUID·스키마·중복 필드·TTL을 재검증한다. 이전 ID는 새 사용자 인덱스를 삭제하지 않는다.
- 실제 HTTP 테스트로 중복 폐기·새 로그인 보존·형식 오류·응답 없음·no-store를 검증했다.
- 폐기 성공 응답 유실과 재시도 사이의 새 로그인, 3회 실패의 미확인 응답, GET 지연 뒤 삭제 방지를 검증했다.

Java 21, 격리 PostgreSQL 18.4 환경에서 `./gradlew --no-daemon --max-workers=2 build`가 통과했다.
Redis 7.4-alpine과 Valkey 9.0.6-alpine에서 각각 전체 166개 테스트가 통과했다. Spotless·패키징을 포함한다.
첫 전체 실행은 새 오류 코드에 대한 기존 오류 표 테스트 누락 1건이 있었고, 계약 기대값을 추가한 뒤 전체 재실행이 통과했다.

이 결과는 Auth Work 범위의 구현·검증이다. BFF의 실제 활동 경합은 BFF·통합 Work에서 검증하며,
서비스 JWT 코드·기동 설정과 구 API 제거는 LOREKEEPER-588에서 수행한다.
