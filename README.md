# loresentry-authentication

> **책임:** 서비스를 소개하고 로컬 실행·설정·빌드·테스트·배포 방법을 안내한다.
>
> **확인할 때:** 개발 환경을 준비하거나 서버·검증 도구를 실행할 때.

Authentication service for Lore Sentry.

Handles the Google-based sign-in flow, user account and display name data, and
authentication session logic used by the Gateway/BFF.

See the [provided API](docs/API.md) and [outbound API calls](docs/API_CALLS.md) for the two API roles.
See the [documentation index](docs/README.md) for Auth contracts, implementation
and verification, and the [code reading guide](docs/code-guide.md) for the login
request flow, startup configuration and related test locations.

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
| Web stack | `spring-boot-starter-webmvc` | Servlet MVC |
| Concurrency | Virtual threads | `spring.threads.virtual.enabled=true` |
| Build | Gradle 9.7.1 (wrapper) | No local Gradle install needed — use `./gradlew`. |
| Container base | `eclipse-temurin:21-jdk-alpine` → `21-jre-alpine` | Multi-stage; the runtime image carries only the JRE. |
| Port | 8000 | Platform convention; Spring's own default is 8080. |

## Run locally

Java 21, PostgreSQL 18 and Redis are required. Flyway creates the account tables and
Hibernate validates them at startup. Before upgrading a database, check the
[migration requirements](docs/account/AUTH_ERD.md#마이그레이션-실행-조건). Supply the following environment variables
before starting the application; `.env` files are not loaded automatically.

| Configuration | Environment variables |
| --- | --- |
| PostgreSQL | `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD` |
| Redis | `SPRING_DATA_REDIS_HOST`, `SPRING_DATA_REDIS_PORT`, `SPRING_DATA_REDIS_PASSWORD` when required |
| Terms consent | `AUTH_TERMS_ENABLED` (default `false`; enable after approved originals and compatible BFF/frontend deployment) |
| Google OAuth | `AUTH_GOOGLE_CLIENT_ID`, `AUTH_GOOGLE_CLIENT_SECRET`, `AUTH_GOOGLE_REDIRECT_URI` |

Defaults are PostgreSQL `localhost:5432/authentication`, user
`authentication_svc`, and Redis `localhost:6379`. Register the browser-facing BFF
callback in Google using the [Google request settings](docs/API_CALLS.md#google-요청-설정).

After supplying the database password and Google configuration:

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
curl http://localhost:8000/health
```

Missing or invalid Google configuration fails startup. Google secrets, Redis
credentials and session IDs must not be committed. Redis permissions follow the
[BFF operations guide](../loresentry-gateway/docs/OPERATIONS.md#redis-acl과-연결).
Use [.env.local.example](.env.local.example) for local environment names.

## Code formatting

Spotless formats Java source with google-java-format's AOSP style. Versions and
source paths are defined in `build.gradle`; generated files under `build/` are excluded.

```bash
./gradlew spotlessApply
./gradlew spotlessCheck
```

`spotlessApply` updates source files. `spotlessCheck` checks without changing them
and runs as part of `./gradlew build` and CI.

## Test

```bash
./gradlew build
AUTH_TEST_REDIS_IMAGE=valkey/valkey:9.0.6-alpine ./gradlew build
```

Tests require Java 21 and Docker. Testcontainers creates disposable PostgreSQL 18.4
and Redis 7.4 instances on random ports (or the image selected by `AUTH_TEST_REDIS_IMAGE`), and Google responses come from an in-process
HTTP/JWK server. Tests generate their own RSA keys and do not require Google
credentials or production connection settings. The first run downloads Gradle
dependencies and container images.

Test reports are generated in `build/reports/tests/test/index.html` (HTML) and
`build/test-results/test` (XML).

```bash
./gradlew test --tests '*FullLoginFlowTest' --rerun-tasks
./gradlew test --tests '*InfrastructureTest' --rerun-tasks
```

Verification scenarios are maintained in the [test plan](docs/implementation/TEST_PLAN.md).
Use the [code guide](docs/code-guide.md#검증-위치) to find the relevant test classes.

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

Authentication compatibility checks and recovery follow the [deployment guide](../loresentry-gateway/docs/ROLLOUT.md).
