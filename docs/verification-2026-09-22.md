# Auth verification record

> 이전 구현의 실행 기록이다. 2026-09-26 단일 세션 ID 설계의 구현·검증 완료 근거로 사용하지 않는다. 당시 결과와 수치는 보존한다.

The complete build passed on 2026-09-22: **129 tests, 0 failures, 0 errors and
0 skipped tests**. This run covers LOREKEEPER-506 after rebasing onto the Flyway
schema in `main` and adding the V2 transition to the current account model.
The design source is the Loresentry docs repository's
`auth/implementation/TEST_PLAN.md`; the table below connects every planned area
to executable tests in this repository.

## Evidence

Tests use Java 21, Spring Boot 4.1.1, disposable PostgreSQL 18.4 and Redis 7.4
containers, and a controlled Google HTTP/JWK server. Real HTTP requests reach the
Spring application in the lifecycle and failure regression tests. Fault injection
uses spies around production adapters so actual DB/Redis state can be compared
with the returned error. RSA keys are generated inside the test process.

| Planned verification | Executable evidence |
| --- | --- |
| Fresh V1-to-V2 migration, empty deployed V1 upgrade, unchanged V1 checksum and repeat startup | [MigrationTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/db/MigrationTest.java) |
| Core isolation, fixed Clock and fake ports | [PortContractTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/PortContractTest.java), [AccountServiceTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/AccountServiceTest.java), [LoginServiceTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/LoginServiceTest.java), [RefreshServiceTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/RefreshServiceTest.java), [RevokeServiceTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/RevokeServiceTest.java) |
| PostgreSQL/Redis adapter behavior and disposable infrastructure | [InfrastructureTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/InfrastructureTest.java), [AccountSchemaTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/AccountSchemaTest.java), [OAuthStateStoreTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/adapter/out/redis/OAuthStateStoreTest.java), [RefreshStoreTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/adapter/out/redis/RefreshStoreTest.java) |
| Google and JWT adapter success/failure contracts | [GoogleOidcClientTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/adapter/out/google/GoogleOidcClientTest.java), [JwtKeysTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/JwtKeysTest.java), [JwtTokensTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/JwtTokensTest.java) |
| ArchUnit dependency rules and production constructor wiring | [ArchitectureTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/ArchitectureTest.java), [FullLoginFlowTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FullLoginFlowTest.java) |
| All error codes, malformed JSON and rejected input fields | [ErrorContractTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/web/ErrorContractTest.java), [AuthControllerTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/web/AuthControllerTest.java), [AccountControllerTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/web/AccountControllerTest.java) |
| RT not-executed, unknown-consumption and new-save failure responses | [RefreshServiceTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/RefreshServiceTest.java), [FailureRegressionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FailureRegressionTest.java) |
| Callback true/false/null outcomes and omission from other APIs | [OAuthConsumptionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/adapter/out/redis/OAuthConsumptionTest.java), [ErrorContractTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/web/ErrorContractTest.java), [AuthControllerTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/web/AuthControllerTest.java), [FailureRegressionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FailureRegressionTest.java) |
| Safe 500 responses, sanitized diagnostics and errors before MVC | [ErrorContractTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/web/ErrorContractTest.java) |
| Flyway/Hibernate schema, UUID v7, duplicate identity rollback and requery | [AccountSchemaTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/AccountSchemaTest.java), [AccountConcurrencyTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/AccountConcurrencyTest.java), [FailureRegressionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FailureRegressionTest.java) |
| Display name preservation, email refresh and committed-account survival | [AccountProfileTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/AccountProfileTest.java), [LoginCommitTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/LoginCommitTest.java), [FullLoginFlowTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FullLoginFlowTest.java), [FailureRegressionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FailureRegressionTest.java) |
| OAuth mismatch preserves state; expiry, absence and single consumption | [OAuthConsumptionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/adapter/out/redis/OAuthConsumptionTest.java), [FailureRegressionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FailureRegressionTest.java) |
| PKCE/nonce failure stops account creation and token issuance | [GoogleOidcClientTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/adapter/out/google/GoogleOidcClientTest.java), [LoginServiceTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/LoginServiceTest.java), [FailureRegressionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FailureRegressionTest.java) |
| JWT audience/type/signature/required claims/kid and time boundaries | [JwtTokensTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/JwtTokensTest.java) |
| Key validation, replacement keys and previous-token rejection | [JwtKeysTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/JwtKeysTest.java), [JwtTokensTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/JwtTokensTest.java) |
| One RT rotation winner; failed save never returns tokens or restores old state | [RefreshRotationTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/RefreshRotationTest.java), [RefreshServiceTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/RefreshServiceTest.java), [FailureRegressionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FailureRegressionTest.java) |
| Lost refresh response cannot be recovered using the previous RT | [RefreshRotationTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/RefreshRotationTest.java), [FailureRegressionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FailureRegressionTest.java) |
| Revocation attempts, waits, command/total limits and unknown outcome | [RevokeServiceTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/RevokeServiceTest.java), [RevocationTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/RevocationTest.java), [FailureRegressionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FailureRegressionTest.java) |
| Independent device tokens and the full HTTP login lifecycle | [FullLoginFlowTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FullLoginFlowTest.java), [RevocationTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/RevocationTest.java), [FailureRegressionTest](https://github.com/soma-lorekeeper/loresentry-authentication/blob/5fadd4f/src/test/java/com/loresentry/authentication/FailureRegressionTest.java) |

The PostgreSQL collision test forces two initial lookups to observe an absent
identity. Both HTTP requests return the same committed user ID, while the adapter
test separately checks that the losing transaction leaves no orphan user.

Redis regression tests simulate a command that executes but loses its response.
The consumed key stays deleted and the API reports an unknown result. A separate
case saves the new RT before losing the save response: no token is returned and
the previous RT remains unusable. A failed login save preserves the committed
account. Callback PKCE and nonce failures leave no account behind.

## Reproduce

With Java 21 and Docker available:

```bash
./gradlew --no-daemon build
```

This run used the command above in `eclipse-temurin:21-jdk-alpine` on the Linux
host, with its Docker socket mounted for Testcontainers. To reproduce without a
host JDK, run from the repository root:

```bash
mkdir -p /tmp/loresentry-auth-gradle
docker run --rm --user "$(id -u):$(id -g)" \
  --group-add "$(stat -c %g /var/run/docker.sock)" --network host \
  -e TESTCONTAINERS_HOST_OVERRIDE=localhost -e GRADLE_USER_HOME=/cache \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /tmp/loresentry-auth-gradle:/cache -v "$PWD:$PWD" -w "$PWD" \
  eclipse-temurin:21-jdk-alpine ./gradlew build --no-daemon --max-workers=2
```

Gradle writes machine-readable results to `build/test-results/test` and an HTML
report to `build/reports/tests/test/index.html`. They are generated artifacts and
are not committed. The infrastructure isolation test was also run in two separate
executions during its implementation.

## External conditions not verified

- A live Google application, consent screen, real credentials and Google callback registration.
- Browser cookies, CSRF, multi-tab coordination and the actual BFF integration.
- AWS network isolation, deployed PostgreSQL/Redis availability, production key distribution and deployment.

These conditions are outside the Auth implementation tests. The fixed Google
endpoints and production callback remain configuration contracts; this run did
not contact Google with user credentials or deploy the service.
