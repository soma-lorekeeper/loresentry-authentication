# loresentry-authentication

> 2026-09-26: 단일 세션 ID 로그인·폐기를 구현했다. 마지막 인증 활동 후 14일 만료하며 BFF가 활동 시 연장한다. 실제 브라우저·운영 전환 검증은 후속 작업이다.

Authentication service for Lore Sentry.

Handles the Google-based sign-in flow, user account and display name data, and
authentication session logic used by the Gateway/BFF.

See the [documentation index](docs/README.md) for Auth contracts, implementation
and verification, and the [code reading guide](docs/code-guide.md) for package
responsibilities, the login request flow and startup configuration.

Reached only through `loresentry-gateway` — this service is `ClusterIP` and has
no route from outside the cluster.

```
Cloudflare → ALB → gateway → authentication
```

Browsers never reach this service, so it has no CORS configuration — the gateway is
the only CORS boundary.

## Stack

| | Version | Notes |
| --- | --- | --- |
| Java | **21** (LTS) | Virtual threads are stable here. Toolchain-pinned in `build.gradle`. |
| Spring Boot | **4.1.1** | Same line as `loresentry-gateway` and `loresentry-content`. |
| Spring Framework | 7.0.9 | Pulled in by Boot 4.1.1. |
| Web stack | `spring-boot-starter-webmvc` | **Servlet MVC, not WebFlux.** Boot 4 renamed the old `-web` starter. |
| Concurrency | Virtual threads | `spring.threads.virtual.enabled=true` |
| Build | Gradle 9.7.1 (wrapper) | No local Gradle install needed — use `./gradlew`. |
| Container base | `eclipse-temurin:21-jdk-alpine` → `21-jre-alpine` | Multi-stage; the runtime image carries only the JRE. |
| Port | 8000 | Platform convention; Spring's own default is 8080. |

Boot 4 moved several test annotations. The one this repo uses is
`org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest` — not the Boot 3
`org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest`.

## Endpoints

The `/auth` APIs are internal calls from the BFF. The BFF owns browser cookies,
CSRF, CORS, per-request session verification and inactivity expiration renewal. Account calls trust its `X-User-Id`
header, so the deployment must keep this service unreachable from the internet.

| Method | Path | Result |
| --- | --- | --- |
| `POST` | `/auth/oauth/google/prepare` | Google authorization URL and five-minute login request |
| `POST` | `/auth/oauth/google/callback` | Verified Google identity mapped to an account, session ID and expiry |
| `POST` | `/auth/sessions/revoke` | Conditionally revoke the supplied session ID; return an empty `204` |
| `GET` | `/auth/users/me` | Account profile identified by `X-User-Id` |
| `PATCH` | `/auth/users/me` | Change `display_name` only |
| `GET` | `/health` | Process health |
| `GET` | `/health/db` | Database connectivity |
| `GET` | `/` | Service name |

Request and response fields use snake case. Errors contain `code`, `message` and
`next_action`. Only callback responses include `login_request_consumed`; a null
value means that consumption could not be confirmed. Authentication responses use
`Cache-Control: no-store`. The API contract is maintained in this repository at
[docs/INTERNAL_API.md](docs/INTERNAL_API.md).

Web request and response DTOs are Java records in `adapter.in.web.dto`. Jackson
maps their JSON field names, and Bean Validation checks required values and the
callback's mutually exclusive `code`/`error` fields. Unknown fields and non-string
values for string fields are rejected. Display-name business rules remain in
the domain and retain the `INVALID_DISPLAY_NAME` error.

MapStruct maps callback request DTOs to service inputs through `AuthRequestMapper`
and service results to response DTOs through `AuthResponseMapper`. The response
mapper flattens the callback session ID and calls an explicit Java method to convert
consumption into `true`, `false` or `null`. Unmapped target fields fail compilation.
After `./gradlew compileJava`, generated mappers are available under
`build/generated/sources/annotationProcessor/java/main/`.

`AccountEntityMapper` converts JPA entities and domain objects inside persistence
transactions. It uses constructors for new entities and retains the supplied user
entity for identity associations. Existing nickname, email and timestamp updates
still use the entities' dedicated methods.

## Single active session

Each login generates a canonical 32-byte random session ID. Redis stores its SHA-256
hash in `auth:session:{login}:by-id:<hash>` and the current hash in
`auth:session:{login}:by-user:<uuid>`. Both records expire together 14 days after
login or the last successfully authenticated activity. A new login replaces the
current user index. An older login cannot revoke the new session.

`LoginSessionStore` is implemented by `RedisLoginSessionStore`. Login does not
retry uncertain writes. Revocation uses bounded retries because it is idempotent.
The [session contract](docs/session/SESSION_DESIGN.md) defines outcomes and races;
the BFF [rollout guide](../loresentry-gateway/docs/ROLLOUT.md) defines deployment gates.

## Schema

Flyway runs on startup and applies `src/main/resources/db/migration` to the
`authentication` database.

| Table | Purpose |
| --- | --- |
| `users` | Service user ID, editable display name (up to 50 characters), creation and update timestamps |
| `oauth_identities` | Provider and provider account ID mapped to a user, with an optional email |

OAuth login requests and per-user active sessions are stored in Redis.

V1 is preserved because it has already been applied to the deployed database.
V2 upgrades those empty tables to the current account schema: it removes
`auth_sessions` and the unused Google account/status columns from `users`, then
creates `oauth_identities`. This migration targets the confirmed empty V1 schema;
it does not migrate existing account or session data. A fresh database applies
V1 and then V2. PostgreSQL 18 is required because V1 uses `uuidv7()`.

Other services store `users.id` as a plain value; there are no cross-database
foreign keys.

## Run locally

Java 21, PostgreSQL 18 and Redis are required. Flyway creates the account tables and
Hibernate validates them at startup. Supply the following environment variables
before starting the application; `.env` files are not loaded automatically.

| Configuration | Environment variables |
| --- | --- |
| PostgreSQL | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` |
| Redis | `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `SPRING_DATA_REDIS_PASSWORD` when required |
| Google OAuth | `AUTH_GOOGLE_CLIENT_ID`, `AUTH_GOOGLE_CLIENT_SECRET`, `AUTH_GOOGLE_REDIRECT_URI` |

Defaults are PostgreSQL `localhost:5432/authentication`, user
`authentication_svc`, and Redis `localhost:6379`. Production Google callbacks use
`https://api.loresentry.com/auth/oauth/google/callback`. With the `local` profile, an
HTTP callback is allowed only on localhost or a loopback address. Register the
same callback in Google and point it at the browser-facing BFF.

After supplying the database password and Google configuration:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
curl http://localhost:8000/health
```

Missing or invalid Google configuration fails startup. Service JWT keys are not
used. Google secrets, Redis credentials and session IDs must not be committed.
`GoogleProperties` binds `auth.google` and validates the callback URL at startup.
The Redis account needs the commands listed in the session and operations docs.
Use [.env.local.example](.env.local.example) for local environment names.

## Code formatting

Spotless is a Gradle build plugin, not an application runtime dependency.
`build.gradle` pins Spotless and google-java-format versions and selects AOSP
style with four-space indentation. It formats Java files under `src/main/java`
and `src/test/java`; generated sources under `build/` are excluded.

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
```

`spotlessApply` rewrites source files; `spotlessCheck` reports violations without
changing files. The check also runs as part of `./gradlew build`, including the
existing CI build. Use the Gradle task as the formatting reference across editors.

## Test

The session implementation passes 108 tests as recorded in
[LOREKEEPER-589](docs/implementation/LOREKEEPER-589.md).
[TEST_COVERAGE.md](TEST_COVERAGE.md) preserves the earlier implementation record.

```bash
./gradlew build
AUTH_TEST_REDIS_IMAGE=valkey/valkey:9.0.6-alpine ./gradlew build
```

Tests require Java 21 and Docker. Testcontainers creates disposable PostgreSQL 18.4
and Redis 7.4 instances on random ports (or the image selected by `AUTH_TEST_REDIS_IMAGE`), and Google responses come from an in-process
HTTP/JWK server. Tests generate their own RSA keys and do not require Google
credentials or production connection settings. The first run downloads Gradle
dependencies and container images.

Spring integration tests enable Flyway at startup to create the schema before
Hibernate validates it. `MigrationTest` runs without a Spring context and invokes
Flyway directly: it checks both a fresh V1-to-V2 installation and an upgrade from
empty V1 tables, including preservation of the V1 checksum.

```bash
./gradlew test --tests '*FullLoginFlowTest' --rerun-tasks
./gradlew test --tests '*InfrastructureTest' --rerun-tasks
```

The full HTTP test covers preparation, callback, profile editing, repeat login,
session replacement and logout with one active session per user. Unit and adapter
tests cover validation, database races, Redis command outcomes, time limits and
error responses. ArchUnit enforces dependency boundaries: `domain` and
`application` compiled classes depend only on core contracts and Java; `config`
wires real adapters to services. Lombok's `@RequiredArgsConstructor` generates
simple dependency-injection constructors at compile time. Constructors with
initialization logic remain explicit. `lombok.config` disables generated Lombok
annotations so the core bytecode keeps this dependency boundary. Jackson remains
in the web adapter; MapStruct is used by the web and persistence adapters. Bean
Validation is used by web DTOs and configuration properties.

These tests do not validate a real Google consent screen, browser/BFF behavior,
production network isolation, or deployed infrastructure and credentials.

## Deploy

`main` push runs [`.github/workflows/ci-cd.yaml`](.github/workflows/ci-cd.yaml):

```
test → docker build → ECR authentication/api:build-<run>-<attempt>
     → invoke loresentry-update-gitops → commit to loresentry-gitops → Argo CD
```

CI never touches Kubernetes. The image tag in the GitOps repository's
`workload/overlays/prod/kustomization.yaml` is the deployment record, and a
rollback is `git revert` of that commit.

Deployed to the `prod` namespace of the `lore-sentry-k8s` EKS cluster via Argo CD.
