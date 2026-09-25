# LOREKEEPER-581 검증 기록

Google 로그인 콜백은 계정 DB 커밋과 세션 저장 성공 후 `session_id`, `expires_at`,
`login_request_consumed`만 반환한다. 충돌이 확인된 경우에만 새 ID로 다시 생성하며,
저장 오류·손상·결과 불명에서는 `LOGIN_UNAVAILABLE`을 반환한다.

Java 21과 격리 PostgreSQL 18.4·Redis 7.4 환경에서 61개 테스트가 통과했다.

- LoginServiceTest 6개: 실행 순서, 확인된 충돌 재생성, 생성 실패, 취소, 단계별 실패, 미확인 소비.
- LoginCommitTest 1개: 세션 실패 후 계정 커밋 보존과 재로그인 UUID 유지.
- AuthControllerTest 42개: 응답 필드·no-store·쿠키 비발급, 입력 검증과 소비 true/false/null 유지.
- FullLoginFlowTest 2개: 실제 설정 연결, Google 테스트 공급자 콜백·계정·표시 이름·세션 교체.
- FailureRegressionTest 8개: 동시 가입/콜백, 소비·저장 응답 유실, 손상, nonce·PKCE·state, 민감값 비노출.
- ArchitectureTest 2개: 코어 의존 경계 유지.

```bash
./gradlew --no-daemon --max-workers=2 spotlessApply test --tests '*LoginServiceTest' --tests '*LoginCommitTest' --tests '*AuthControllerTest' --tests '*FullLoginFlowTest' --tests '*FailureRegressionTest' --tests '*ArchitectureTest'
```

Spotless 적용 후 diff를 확인했다. 실제 Google 동의 화면·BFF 쿠키 왕복은 이번 테스트에 포함하지 않는다.
새 폐기 API는 후속 LOREKEEPER-582에서 연결하고 기존 토큰 실행 의존성 정리는 LOREKEEPER-588에서 수행한다.
