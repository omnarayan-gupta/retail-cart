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

Prerequisite: Docker. (To build from source you also need JDK 21, or let Gradle download it.)

```bash
docker compose up -d --build      # Postgres + Target product services + cart app
curl http://localhost:8081/actuator/health

# Add an item (creates the cart), then view it with live prices
curl -i -X POST http://localhost:8081/api/v1/carts/items \
     -H 'Content-Type: application/json' -d '{"itemId":"54276335","quantity":2}'
curl http://localhost:8081/api/v1/carts/<cartId from the response>

docker compose down               # stop (add -v to also delete cart data)
```

### Check it works

```bash
scripts/smoke.sh            # 25 end-to-end checks with real catalog data (bash + curl only)
scripts/smoke.sh --outage   # also stops the product services: the cart degrades, then recovers
```

`http/cart.http` has the same requests to run one by one from the IDE.

| Service | URL |
|---|---|
| Cart service (this project) | http://localhost:8081/api/v1/carts |
| Target product services | http://localhost:8080/product/v1 |
| PostgreSQL | localhost:5432 (db/user/password `retailcart`) |

## Development

```bash
./gradlew build                     # compile, check formatting, run all tests (Docker needed for Testcontainers)
./gradlew spotlessApply             # fix formatting
docker compose up -d postgres product && ./gradlew bootRun   # run from source against the containers
```
