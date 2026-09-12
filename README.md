# loresentry-authentication

Authentication service for Lore Sentry.

Handles the Google-based sign-in flow, user account and display name data, and
authentication session/token logic used by the Gateway/BFF.

- Stack: Spring Boot
- Database: MySQL
- Deployed to the `prod` namespace of the `lore-sentry-k8s` EKS cluster via Argo CD
