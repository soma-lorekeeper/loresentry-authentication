# 로그인 설계

Google 로그인 준비부터 신원 검증·계정 확인·세션 생성까지의 흐름을 관리한다.

| 문서 | 내용 |
|---|---|
| [로그인 흐름](LOGIN_FLOW.md) | 처리 순서와 계정 커밋 후 세션 저장 실패 정책 |
| [OAuth 상태](OAUTH_STATE.md) | 브라우저 연결 검증과 임시 상태의 일회성 소비 |

로그인 흐름을 먼저 읽고 OAuth 상태 계약을 확인한다. 요청 설정과 직렬화는 [구현 참고](../implementation/README.md)를 따른다.
전체 안내는 [Auth README](../README.md)를 참고한다.
