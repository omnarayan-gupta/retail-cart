# Retail Cart Service

RESTful shopping-cart service (add, update quantity, remove, view) built on Java 21 and Spring Boot 4.2.
It depends on the Target product services (Item, Pricing, Availability) and always shows the latest price.

> Work in progress. Full run instructions, API reference, design decisions and assumptions will be added here.

## Project layout

| Path | Purpose |
|---|---|
| `build.gradle.kts` | Single Gradle build for the service |
| `gradle/libs.versions.toml` | All dependency versions in one place |
| `src/main/java/.../app/api` | REST controller, request/response DTOs, error handling |
| `src/main/java/.../app/application` | Use cases: add/update/remove, cart view |
| `src/main/java/.../app/domain` | Cart rules (plain Java) and ports (interfaces) |
| `src/main/java/.../app/infrastructure` | Adapters: PostgreSQL (`persistence`), product service clients (`client`), config |
| `src/main/resources` | `application.yml`, Flyway migrations |

## Quick start

```bash
./gradlew build          # compile, check formatting, run tests
./gradlew spotlessApply  # fix formatting
```
