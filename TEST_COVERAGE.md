# Auth verification record

On 2026-09-24, LOREKEEPER-535 passed the complete build with **152 tests,
0 failures, 0 errors and 0 skipped tests** on both Redis 7.4 and Valkey 9.0.6.
The implementation and test baseline is commit `2bdfaf0` on `work/LOREKEEPER-545`.
The full deliverable is published to `deliverable/LOREKEEPER-535`; production rollout is separate.

## Environment and evidence

Both runs used Java 21 (`eclipse-temurin:21-jdk-alpine`), Spring Boot 4.1.1,
PostgreSQL `18.4-alpine`, generated RSA keys and a controlled Google HTTP/JWK server.
Testcontainers starts disposable services on random ports and does not read production
connection settings. The Valkey image is `valkey/valkey:9.0.6-alpine`, matching the
version in the GitOps Auth manifest. Its tested digest is
`sha256:187679e3bd4036959631e3f03983ab2ba503ab21e6fd0454d508e909db2ee989`.

| Verified behavior | Executable evidence |
| --- | --- |
| AT/RT share sid, independent jti, RS256, required claims, clocks and key rotation | [JwtTokensTest](src/test/java/com/loresentry/authentication/JwtTokensTest.java), [JwtKeysTest](src/test/java/com/loresentry/authentication/JwtKeysTest.java) |
| Account commits before session replacement; signing failures preserve state | [LoginServiceTest](src/test/java/com/loresentry/authentication/LoginServiceTest.java), [LoginCommitTest](src/test/java/com/loresentry/authentication/LoginCommitTest.java) |
| JSON schema, Lua TIME/PXAT, stale sid/jti/expiry rejection, corruption and command deadlines | [SessionStoreTest](src/test/java/com/loresentry/authentication/adapter/out/redis/SessionStoreTest.java) |
| Independent connections: simultaneous logins/rotations, both orders of login/refresh/revoke, no observed gap during rotations | [SessionRaceTest](src/test/java/com/loresentry/authentication/adapter/out/redis/SessionRaceTest.java), [SessionStoreTest](src/test/java/com/loresentry/authentication/adapter/out/redis/SessionStoreTest.java) |
| Same-sid rotation, one winner, sliding 14-day expiry, state loss and no recovery | [RefreshServiceTest](src/test/java/com/loresentry/authentication/RefreshServiceTest.java), [RefreshRotationTest](src/test/java/com/loresentry/authentication/RefreshRotationTest.java) |
| Rotated unexpired RT revokes its session, expired RT is a no-op, retries preserve new login and expiry guards | [RevokeServiceTest](src/test/java/com/loresentry/authentication/RevokeServiceTest.java), [RevocationTest](src/test/java/com/loresentry/authentication/RevocationTest.java) |
| HTTP lifecycle, user isolation, active sid match, old AT sid mismatch, sidless RT rejection and no legacy-key fallback | [FullLoginFlowTest](src/test/java/com/loresentry/authentication/FullLoginFlowTest.java) |
| Not-executed vs unknown rotation, signing/corruption 500, no secret leakage, callback consumption, no replay | [FailureRegressionTest](src/test/java/com/loresentry/authentication/FailureRegressionTest.java), [ErrorContractTest](src/test/java/com/loresentry/authentication/web/ErrorContractTest.java), [RedisDriverTest](src/test/java/com/loresentry/authentication/adapter/out/redis/RedisDriverTest.java) |
| OAuth/PKCE/nonce, account races/profile, Flyway V1/V2, request DTO validation and architecture regressions | Existing Google/OAuth, Account, Migration, Controller and [ArchitectureTest](src/test/java/com/loresentry/authentication/ArchitectureTest.java) suites in the same full build |

The race tests use barriers/latches to control order across independent clients.
Timeout and lost-response tests inject adapter failures and compare actual state;
they do not simulate a production network partition or failover.
The AT assertions inspect the signed sid and shared record, not a live BFF route.

## Reproduce

With Java 21 and Docker:

```bash
./gradlew --no-daemon --max-workers=2 build
AUTH_TEST_REDIS_IMAGE=valkey/valkey:9.0.6-alpine ./gradlew --no-daemon --max-workers=2 build
```

The selected image is a Gradle test input, so changing it reruns the tests.
With no host JDK, run from this repository (set `AUTH_TEST_REDIS_IMAGE` to select Valkey):

```bash
mkdir -p /tmp/loresentry-auth-gradle
docker run --rm --user "$(id -u):$(id -g)" \
  --group-add "$(stat -c %g /var/run/docker.sock)" --network host \
  -e AUTH_TEST_REDIS_IMAGE -e TESTCONTAINERS_HOST_OVERRIDE=localhost -e GRADLE_USER_HOME=/cache \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /tmp/loresentry-auth-gradle:/cache -v "$PWD:$PWD" -w "$PWD" \
  eclipse-temurin:21-jdk-alpine ./gradlew build --no-daemon --max-workers=2
```

Machine-readable results are in `build/test-results/test`; HTML is in
`build/reports/tests/test/index.html`. Generated reports are not committed.
Spotless and architecture checks run in the full build.

## External conditions not verified

BFF protection, cookies/CSRF/multiple tabs, live Google consent and credentials,
production ACL/client initialization, network isolation, failover and deployment
remain follow-up work. See the docs repository's `auth/implementation/SESSION_HANDOFF.md`.
Auth completion alone does not prove immediate rejection of an old AT at a protected API.

## Previous verification

The [2026-09-22 record](docs/verification-2026-09-22.md) preserves the earlier
129-test result for LOREKEEPER-506. Its device-preserving RT policy and separate
GETDEL/save checks describe that earlier implementation, not the current contract.
