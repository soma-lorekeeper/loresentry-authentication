# Auth 문서

Auth의 서비스별 설계·구현·검증 문서는 이 저장소의 `docs/`에서 관리한다.
서비스 간 책임과 공동 배포·전환 계약은 공통 문서 저장소를 링크로 참조한다.

**2026-09-24 Auth 단일 세션 구현:** LOREKEEPER-535에서 로그인 교체·RT 원자 갱신·sid 조건부 폐기를 구현했다.
Redis 7.4·Valkey 9.0.6에서 각각 152개 테스트가 통과했다.
공유 계약은 [단일 로그인 세션](token/SINGLE_SESSION_DESIGN.md),
남은 BFF·프론트·ACL·배포 작업은 [전환 인계](../../docs/auth/implementation/SESSION_HANDOFF.md)를 따른다.
Auth 완료만으로 이전 AT의 실제 보호 API 접근 차단이 완료된 것은 아니다.

## 이전 구현 기록 — 2026-09-22

**Auth의 Google 로그인·계정·토큰 구현은 2026-09-22 전체 빌드와 테스트를 통과했다.**
기준은 `loresentry-authentication`의 로컬 `main` 커밋 `5fadd4f`이며,
`main`의 Flyway V1을 기반으로 현재 계정 구조로 전환하는 V2까지 검증했다.
원격 push와 운영 배포는 하지 않았다. 운영 DB에는 V1의 빈 테이블만 있다.
검증 범위와 결과는 [테스트 계획](implementation/TEST_PLAN.md#실행-결과),
운영 스키마와의 차이는 [반영 현황](../../docs/TABLE_AND_LOGIC.md#9-스키마-반영-현황)을 참고한다.

책임과 구조를 먼저 읽고, 작업할 기능의 설계를 확인한다. 세부 구현 방법과 검증 목록은 필요할 때 참고한다.

## 공통 설계

| 문서 | 읽을 때 |
|---|---|
| [Auth 책임](../../docs/auth/AUTH_RESPONSIBILITIES.md) | 담당 기능과 BFF 경계 확인 |
| [애플리케이션 구조](ARCHITECTURE.md) | 패키지·포트·어댑터 구성 |
| [내부 API](INTERNAL_API.md) | BFF 요청·응답·오류 연동 |
| [코드 읽기](code-guide.md) | 요청 흐름과 설정에서 구현을 따라가는 순서 |
| [검증 기록](../TEST_COVERAGE.md) | 실행한 테스트·환경과 재현 방법 |
| [이전 검증 기록](verification-2026-09-22.md) | 2026-09-22 구현의 검증 이력 |

## 기능별 문서

| 폴더 | 역할 |
|---|---|
| [account/](account/README.md) | 계정 데이터 구조와 영속성 |
| [login/](login/README.md) | Google 로그인과 OAuth 임시 상태 |
| [token/](token/README.md) | JWT와 Refresh Token 정책 |
| [implementation/](implementation/README.md) | 구현 세부사항과 테스트 계획 |

폴더 내부의 문서 목록과 읽는 순서는 각 README에서 안내한다.
동작 정책은 기능별 설계 문서, 기술별 세부 선택과 검증은 `implementation/`에서 관리한다.

## 후속 작업의 범위

- **연동 검증:** 실제 Google·브라우저·BFF 연동을 확인한다. Google 등록 URL과 배포 설정은 [OAuth 요청 설정](implementation/IMPLEMENTATION_NOTES.md#oauth-요청-설정)의 콜백 경로에 맞춘다.
- **배포 전:** [키 주입 협의](token/JWT_DESIGN.md#배포-시-협의-사항)와 [내부 접근 제한 검증](../../docs/bff/INTERNAL_SERVICE_CALLS.md#내부-서비스-호출)을 수행한다.
- **별도 설계:** [BFF 상세 설계](../../loresentry-gateway/docs/auth/README.md#남은-설계), [자체 로그인·계정 연결](account/AUTH_ERD.md#4-이후-기능).

공통 맥락은 [루트 문서](../../docs/README.md), BFF는 [해당 폴더](../../loresentry-gateway/docs/README.md)를 참고한다.
