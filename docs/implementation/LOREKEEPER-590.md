# LOREKEEPER-590 실행 안내 갱신

2026-09-26 README와 로컬 환경 예제를 단일 세션 로그인·폐기에 맞췄다.
서비스 JWT 키 생성·주입 안내를 제거하고 현재 구현 상태를 갱신했다.
기존 CI·Dockerfile은 키 주입 없는 Gradle 빌드 경로를 그대로 사용한다.

Auth 커밋 9c0613f의 git archive 복사본을 Java 21에서 빌드하고 PostgreSQL 18.4·
Valkey 9.0.6·실제 BFF 두 인스턴스와 로그인·계정·교체·폐기를 검증했다.
외부 Google만 테스트 HTTP/JWK 서버로 대체했다. 상세 실행 결과와 재현 명령은
[BFF 기록](../../../loresentry-gateway/docs/verification/LOREKEEPER-590.md)과
[통합 실행 안내](../../../loresentry-gateway/integration/session/README.md)를 따른다.
검증 자원은 실행 후 정리했다. 실제 브라우저·운영 검증은 포함하지 않는다.

이전 테스트 기록과 적용된 Flyway 파일은 변경하지 않았다.
