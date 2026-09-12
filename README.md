# loresentry-authentication

Authentication service for Lore Sentry.

Handles the Google-based sign-in flow, user account and display name data, and
authentication session/token logic used by the Gateway/BFF.

Reached only through `loresentry-gateway` — this service is `ClusterIP` and has
no route from outside the cluster.

```
Cloudflare → ALB → gateway → authentication
```

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

| Method | Path | Behaviour |
| --- | --- | --- |
| `GET` | `/health` | `{"status":"ok"}`. Used by the Kubernetes probes. |
| `GET` | `/` | `{"service":"authentication-api"}` |

The gateway exposes this service publicly at `GET /auth`, which calls `/` here
and returns the payload nested under `upstream`.

## Run locally

```bash
./gradlew bootRun
curl localhost:8000/health
```

## Test

```bash
./gradlew build
```

Covers context startup, that virtual threads are actually enabled, and both
endpoints through `MockMvc`. No AWS or network access required.

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

## Not implemented yet

- PostgreSQL persistence for accounts and sessions. The database is not
  provisioned yet, so no driver, ORM or migration tool is wired in — adding one
  before the database exists would only make the container fail to start with
  `Failed to configure a DataSource`.
- The Google OAuth sign-in flow.
- Token issuance, and the verification side that `loresentry-gateway` needs.
