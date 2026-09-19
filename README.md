# loresentry-authentication

Authentication service for Lore Sentry.

Handles the Google-based sign-in flow, user account and display name data, and
authentication session/token logic used by the Gateway/BFF.

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
CSRF, CORS and access-token verification. Account calls trust its `X-User-Id`
header, so the deployment must keep this service unreachable from the internet.

| Method | Path | Result |
| --- | --- | --- |
| `POST` | `/auth/oauth/google/prepare` | Google authorization URL and five-minute login request |
| `POST` | `/auth/oauth/google/callback` | Verified Google identity mapped to a service account and tokens |
| `POST` | `/auth/tokens/refresh` | Consume one RT and save a new AT/RT pair |
| `POST` | `/auth/tokens/revoke` | Revoke the supplied RT; return an empty `204` |
| `GET` | `/auth/users/me` | Account profile identified by `X-User-Id` |
| `PATCH` | `/auth/users/me` | Change `display_name` only |
| `GET` | `/health` | Process health |
| `GET` | `/health/db` | Database connectivity |
| `GET` | `/` | Service name |

Request and response fields use snake case. Errors contain `code`, `message` and
`next_action`. Only callback responses include `login_request_consumed`; a null
value means that consumption could not be confirmed. Token responses use
`Cache-Control: no-store`. The API contract is maintained in the Loresentry docs
repository at `auth/INTERNAL_API.md`.

Web request and response DTOs are Java records in `adapter.in.web.dto`. Jackson
maps their JSON field names, and Bean Validation checks required values and the
callback's mutually exclusive `code`/`error` fields. Unknown fields and non-string
values for string fields are rejected. Display-name business rules remain in
the domain and retain the `INVALID_DISPLAY_NAME` error.

## Run locally

Java 21, PostgreSQL and Redis are required. Flyway creates the account tables and
Hibernate validates them at startup. Supply the following environment variables
before starting the application; `.env` files are not loaded automatically.

| Configuration | Environment variables |
| --- | --- |
| PostgreSQL | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` |
| Redis | `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `SPRING_DATA_REDIS_PASSWORD` when required |
| JWT private key | `AUTH_JWT_PRIVATE_KEY_BASE64`: unencrypted PKCS#8 DER encoded as one-line Base64 |
| JWT public key | `AUTH_JWT_PUBLIC_KEY_PATH`: path to a readable SPKI PEM public key |
| JWT key identity | `AUTH_JWT_KEY_ID`: persistent UUID v4 for this key pair |
| Google OAuth | `AUTH_GOOGLE_CLIENT_ID`, `AUTH_GOOGLE_CLIENT_SECRET`, `AUTH_GOOGLE_REDIRECT_URI` |

Defaults are PostgreSQL `localhost:5432/authentication`, user
`authentication_svc`, and Redis `localhost:6379`. Production Google callbacks use
`https://api.loresentry.com/auth/callback/google`. With the `local` profile, an
HTTP callback is allowed only on localhost or a loopback address. Register the
same callback in Google and point it at the browser-facing BFF.

Generate local test keys once with OpenSSL on the Linux host. Keep these files
between restarts; repeat generation only when intentionally replacing the keys.
Both `.local/` and local environment files are excluded from Git and Docker build
contexts.

```bash
umask 077
mkdir -p .local
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out .local/auth-private.pem
openssl pkey -in .local/auth-private.pem -pubout -out .local/auth-public.pem
cat /proc/sys/kernel/random/uuid > .local/auth-kid
export AUTH_JWT_PRIVATE_KEY_BASE64="$(openssl pkcs8 -topk8 -nocrypt -in .local/auth-private.pem -outform DER | base64 -w0)"
export AUTH_JWT_PUBLIC_KEY_PATH="$PWD/.local/auth-public.pem"
export AUTH_JWT_KEY_ID="$(cat .local/auth-kid)"
```

After supplying the database password and Google configuration:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
curl http://localhost:8000/health
```

Missing or invalid JWT/Google configuration fails startup. Private keys, Google
secrets and token values must not be committed. Share only the public key and key
ID with the BFF.

## Test

See [the verification record](TEST_COVERAGE.md) for design coverage and the
recorded test result.

```bash
./gradlew build
```

Tests require Java 21 and Docker. Testcontainers creates disposable PostgreSQL
and Redis instances on random ports, and Google responses come from an in-process
HTTP/JWK server. Tests generate their own RSA keys and do not require Google
credentials or production connection settings. The first run downloads Gradle
dependencies and container images.

```bash
./gradlew test --tests '*FullLoginFlowTest' --rerun-tasks
./gradlew test --tests '*InfrastructureTest' --rerun-tasks
```

The full HTTP test covers preparation, callback, profile editing, repeat login,
refresh and logout while preserving another device's token. Unit and adapter
tests cover validation, database races, Redis command outcomes, time limits and
error responses. ArchUnit enforces dependency boundaries: `domain` and
`application` compiled classes depend only on core contracts and Java; `config`
wires real adapters to services. Lombok's `@RequiredArgsConstructor` generates
simple dependency-injection constructors at compile time. Constructors with
initialization logic remain explicit. `lombok.config` disables generated Lombok
annotations so the core bytecode keeps this dependency boundary. Jackson and
Bean Validation annotations are confined to the web DTOs.

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
