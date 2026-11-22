# Retail Cart Service

RESTful shopping-cart service (add, update quantity, remove, view) built on Java 21 and Spring Boot 4.2.
It depends on the Target product services (Item, Pricing, Availability) and always shows the latest price.

> Work in progress. Full run instructions, API reference, design decisions and assumptions will be added here.

## Project layout

| Path | Purpose |
|---|---|
| `apps/app` | The cart service (Spring Web MVC, port 8081) |
| `libs/product-client` | Reusable clients for the Item, Pricing and Availability services |
| `gradle/libs.versions.toml` | All dependency versions in one place |

## Quick start

```bash
./gradlew build          # compile, check formatting, run tests
./gradlew spotlessApply  # fix formatting
```
