# Retail Cart Service

A REST API for a guest shopping cart: **add, update quantity, remove and view items**. It is built
with Java 21 and Spring Boot 4 (Spring Web MVC) and depends on the Target product services (Item,
Pricing, Availability).

The three rules from the brief, and how the service meets them:

| Rule | How |
|---|---|
| Only available items can be added | Every add (and every quantity increase) checks the Availability service first, and enforces the item's purchase limit |
| Price and availability change in real time | The cart stores only item ids and quantities, never prices or availability |
| The cart always shows the latest price | Every `GET /carts/{id}` fetches current prices and availability, in parallel, and never falls back to an old value |

More detail: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) (diagrams and request flows),
[docs/adr](docs/adr) (key decisions) and [docs/DECISIONS.md](docs/DECISIONS.md) (full decision log).

---

## Contents

1. [Prerequisites](#1-prerequisites)
2. [Run it: command line](#2-run-it-command-line)
3. [Run it: IntelliJ IDEA](#3-run-it-intellij-idea)
4. [Test it](#4-test-it)
5. [API reference](#5-api-reference)
6. [Configuration](#6-configuration)
7. [Project layout](#7-project-layout)
8. [Assumptions and open questions](#8-assumptions-and-open-questions)
9. [Not included / before production](#9-not-included--before-production)
10. [Troubleshooting](#10-troubleshooting)

---

## 1. Prerequisites

| Tool | Needed for | Check |
|---|---|---|
| **Docker** (Docker Desktop on Windows/macOS) | Running everything, and the database tests | `docker version` shows a *Server* section |
| **Git** | Getting the code | `git --version` |
| **JDK 21** | Building and running from source or the IDE. Not needed for the Docker-only path | `java -version` |
| **bash + curl** | The smoke test script. macOS/Linux have them; on Windows use **Git Bash** (installed with Git) | `bash --version` |

You don't need to install Gradle: the project includes the Gradle wrapper (`gradlew` / `gradlew.bat`),
which downloads the right version. If JDK 21 is missing, the build downloads one automatically.

Ports used: **8081** (cart service), **8080** (Target product services), **5432** (PostgreSQL).

---

## 2. Run it: command line

### Get the code

```bash
git clone <repository-url> retail-cart      # or unzip the submission
cd retail-cart
```

### Option A: run everything in Docker (fastest, no JDK needed)

```bash
docker compose up -d --build
```

The first run takes a few minutes (it downloads images and builds the app). Then check that all three
containers are healthy:

```bash
docker compose ps
```

```
SERVICE    STATUS
cart       Up (healthy)
postgres   Up (healthy)
product    Up (healthy)
```

Try it:

```bash
# Add an item: this creates a cart (201) and returns its id
curl -i -X POST http://localhost:8081/api/v1/carts/items \
     -H "Content-Type: application/json" \
     -d '{"itemId":"54276335","quantity":2}'

# View the cart with live prices (use the cartId from the response above)
curl http://localhost:8081/api/v1/carts/<cartId>
```

Or open **Swagger UI**: <http://localhost:8081/swagger-ui.html>

Stop it:

```bash
docker compose down          # keeps cart data
docker compose down -v       # also deletes cart data
```

### Option B: build and run from source

```bash
./gradlew build                            # compile, check formatting, run all 130 tests (Docker must be running)
docker compose up -d postgres product      # start only the database and the product services
./gradlew bootRun                          # start the cart service on http://localhost:8081
```

On **Windows** (PowerShell or cmd), use `gradlew.bat` instead of `./gradlew`.

`make` shortcuts are also available on macOS/Linux: `make build`, `make up`, `make smoke`, `make demo`, `make down`.

---

## 3. Run it: IntelliJ IDEA

Works with IntelliJ IDEA Community or Ultimate.

### Open the project

1. **File → Open…**, select the `retail-cart` folder (the one containing `build.gradle.kts`), click **Open**.
2. If asked, choose **Trust Project**. IntelliJ imports the Gradle build; wait for the progress bar
   at the bottom to finish (first time: a few minutes while dependencies download).
3. Set the JDK to 21:
   - **File → Project Structure → Project → SDK**: pick a **21** JDK. If none is listed, choose
     **Add SDK → Download JDK…**, version 21, any vendor (e.g. Eclipse Temurin).
   - **Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM**: choose the same JDK 21.
4. Optional, for the same formatting as the build: install the **google-java-format** plugin.
   The build checks formatting anyway; `./gradlew spotlessApply` fixes it.

### Start the database and product services

Open the **Terminal** tab (Alt+F12 / ⌥F12) and run:

```bash
docker compose up -d postgres product
```

### Run the app

1. Open `src/main/java/com/target/retailcart/app/Application.java`
   (Ctrl+Shift+N / ⌘⇧O, type `Application`).
2. Click the green ▶ next to `class Application` → **Run 'Application'**.
3. The Run window shows `Started Application in … seconds`. The service is on <http://localhost:8081>.

The defaults in `application.yml` already point at `localhost:5432` and `localhost:8080`, so no
run configuration is needed.

### Run the tests

Docker must be running (database tests start PostgreSQL in a container automatically).

- **All tests**: right-click `src/test/java` → **Run 'All Tests'**, or in the **Gradle** tool
  window (elephant icon): **retail-cart → Tasks → verification → test**.
- **Full build with all checks** (formatting, tests, coverage gate): Gradle tool window →
  **Tasks → build → build**.
- One test class: open it and click ▶ next to the class name.

### Send requests from the IDE

- **Swagger UI**: <http://localhost:8081/swagger-ui.html> ("Try it out" on each endpoint).
- **`http/cart.http`**: if your IntelliJ has the HTTP Client, each request has a ▶ next to it.
  Run request 1 first; it stores the new `cartId` for the others.
- **Smoke script**: in the Terminal, `bash scripts/smoke.sh` (on Windows the Terminal must be Git
  Bash: **Settings → Tools → Terminal → Shell path** → `C:\Program Files\Git\bin\bash.exe`).

---

## 4. Test it

### Automated tests (130)

```bash
./gradlew build      # or: ./gradlew test
```

| Kind | Classes | What they prove |
|---|---|---|
| Domain unit tests | `CartTest` | Cart rules: merging lines, quantity limits, expiry (plain JUnit, milliseconds) |
| Service tests | `CartServiceTest`, `CartViewServiceTest`, `IdempotencyServiceTest`, `AvailabilityPolicyTest`, `PriceResolverTest` | Use cases with fake dependencies: retries, parallel lookups, timeouts, degradation |
| API tests | `CartControllerTest` | HTTP status codes, validation, error format |
| Database tests | `JpaCartRepositoryTest`, `JdbcIdempotencyStoreTest` | Real PostgreSQL: schema, optimistic locking, concurrent idempotency claims |
| HTTP client tests | `ProductClientsTest` | Against WireMock: JSON mapping, 404 handling, retry, timeouts, circuit breaker |
| **End-to-end scenarios** | `CartScenariosTest` | The whole app on a real port: one test per rule in the brief, plus retries, concurrency and tracing |

Reports after a build:
- Tests: `build/reports/tests/test/index.html`
- Coverage: `build/reports/jacoco/test/html/index.html` (the build fails below 90% lines / 80% branches)

### Smoke test against the running system

With the stack running (section 2, option A or B):

```bash
scripts/smoke.sh             # 25 checks with real catalog data
scripts/smoke.sh --outage    # + stops the product services to show degradation and recovery
```

Expected ending: `25 passed, 0 failed` (or `31 passed, 0 failed` with `--outage`).

The `--outage` run stops the product container mid-test and shows that the cart view still works
(flagged incomplete), adds return **503** with `Retry-After`, the cart service stays healthy, and
everything recovers on its own once the circuit breakers close (about 10 seconds).

### Manual checks

Useful item ids from the product data:

| Item id | What it is | Why it's useful |
|---|---|---|
| `54276335` | Hanes tee | SALE price, purchase limit 20 |
| `15744221` | Mrs. Meyer's cleaner | REGULAR price, limit 10 |
| `76180307` | Hape kitchen set | Only 5 in stock, limit 2 |
| `99999999` | (none) | Not in the catalog → 422 |

Browse more: <http://localhost:8080/product/v1/items?page=0&size=20>

---

## 5. API reference

Base URL: `http://localhost:8081/api/v1/carts` · Full contract: Swagger UI at `/swagger-ui.html`,
OpenAPI JSON at `/v3/api-docs`.

| Method | Path | Body | Success |
|---|---|---|---|
| `POST` | `/items` | `{"itemId":"54276335","quantity":2}` | **201**, `Location: /api/v1/carts/{cartId}`: creates the cart |
| `POST` | `/{cartId}/items` | `{"itemId":"…","quantity":1}` | **200**: adds; an item already in the cart has its quantity increased |
| `PATCH` | `/{cartId}/items/{itemId}` | `{"quantity":5}` | **200**: sets the quantity (absolute, not a delta) |
| `DELETE` | `/{cartId}/items/{itemId}` | | **204** |
| `GET` | `/{cartId}` | | **200**: the cart with live prices and availability |

**Write responses** are small: `{"cartId":"…","itemId":"54276335","quantity":3,"itemCount":4}`.
Prices are only returned by `GET`, which always fetches them fresh.

**Idempotency-Key** (optional, on both `POST`s): send a unique value (e.g. a UUID). Repeating the
request with the same key returns the original response with `Idempotent-Replayed: true` instead
of creating a second cart or adding the quantity twice.

**Cart view** (`GET /{cartId}`), abbreviated:

```json
{
  "cartId": "d392313d-824d-4949-bcc0-13d6435c3b4f",
  "items": [
    {
      "itemId": "52493021", "name": "Haggar H26® 4 Way Stretch", "brand": "Haggar H26",
      "quantity": 3, "unitPrice": 20.74, "regularPrice": 29.99, "priceType": "SALE",
      "lineTotal": 62.22, "availability": "AVAILABLE", "maxAllowed": 20, "status": "OK"
    }
  ],
  "itemCount": 3, "subtotal": 62.22, "currency": "USD",
  "totalIncomplete": false, "warnings": [], "pricedAt": "2026-11-24T00:36:09.97Z"
}
```

Line `status`, most important first: `UNAVAILABLE` (sold out; excluded from the subtotal),
`AVAILABILITY_UNKNOWN` / `PRICE_UNAVAILABLE` (a product service didn't answer; excluded and
`totalIncomplete: true`), `QUANTITY_EXCEEDS_LIMIT` (the limit dropped below the cart quantity),
`DETAILS_UNAVAILABLE` (name/image missing), `OK`.

**Errors** are [RFC 7807](https://www.rfc-editor.org/rfc/rfc7807) Problem Details with a `code`:

| Status | `code` | When |
|---|---|---|
| 400 | `VALIDATION_FAILED` | Invalid input; `errors` lists each field |
| 404 | `CART_NOT_FOUND` | Unknown or expired cart |
| 404 | `ITEM_NOT_IN_CART` | Update/remove of an item that isn't in the cart |
| 409 | `ITEM_UNAVAILABLE` | Item is out of stock |
| 409 | `QUANTITY_LIMIT_EXCEEDED` | Over the item's purchase limit; includes `maxAllowed` |
| 409 | `CONCURRENT_MODIFICATION` | Cart changed by another request too many times in a row; retry |
| 409 | `IDEMPOTENCY_KEY_IN_PROGRESS` | The first request with this key is still running; `Retry-After: 1` |
| 422 | `ITEM_NOT_FOUND` | Item id is not in the catalog |
| 422 | `IDEMPOTENCY_KEY_REUSED` | Same key sent with a different request |
| 503 | `DEPENDENCY_UNAVAILABLE` | A product service needed for a write is down; `Retry-After: 5` |
| 500 | `INTERNAL_ERROR` | Unexpected; no internal details are exposed |

Operational endpoints: `/actuator/health` (plus `/liveness`, `/readiness`), `/actuator/info`,
`/actuator/metrics`.

---

## 6. Configuration

Defaults are in `src/main/resources/application.yml`. Override with environment variables:

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/retailcart` | Database |
| `DB_USERNAME` / `DB_PASSWORD` | `retailcart` / `retailcart` | Database credentials (local only; inject secrets in real environments) |
| `PRODUCT_SERVICE_URL` | `http://localhost:8080` | Target product services |
| `SERVER_PORT` | `8081` | HTTP port |
| `LOGGING_STRUCTURED_FORMAT_CONSOLE` | (plain text) | Set to `ecs` for JSON logs (Docker does this) |

Other settings in `application.yml`: cart lifetime (`cart.ttl` 30 days, `cart.empty-ttl` 24 hours),
product service timeouts (250 ms connect, 400 ms read), retry and circuit breaker settings, and
idempotency key lifetime (24 hours).

---

## 7. Project layout

```
retail-cart/
├── build.gradle.kts, settings.gradle.kts   Gradle build (single module)
├── gradle/libs.versions.toml               all dependency versions
├── Dockerfile, docker-compose.yml          container image; full local stack
├── scripts/smoke.sh, http/cart.http        end-to-end checks; requests for the IDE
├── docs/                                   architecture, decision records, decision log
└── src/main/java/com/target/retailcart/app/
    ├── api/              REST controller, request/response types, error handling
    ├── application/      use cases: add/update/remove (CartService), view (view/), idempotency/
    ├── domain/           the Cart and its rules (plain Java), and port/ interfaces it needs
    └── infrastructure/   adapters: persistence/ (PostgreSQL), client/ (product services), config/
```

Dependencies point inwards: `domain` uses nothing from the other packages, so the business rules
can be tested in isolation and the database or a product service can change without touching them.

---

## 8. Assumptions and open questions

Decisions made where the brief or the product API left room. Each is isolated so it can change
easily, and the questions have been raised with the team.

| # | Question | Assumption in this build | Evidence from the product data |
|---|---|---|---|
| Q1 | Is `limited_quantity_threshold` a per-cart purchase limit or a "low stock" hint? | Purchase limit: max quantity = min(`available_units`, threshold) | Always below stock, in tiers by stock level: 5–14 units → 2, 23–95 → 5, 102–296 → 10, 312+ → 20 |
| Q2 | Which `price_type` values exist, and is `sale` the price to charge for `SALE`? | `SALE` → sale price, anything else → regular price | Only `SALE` (59 items) and `REGULAR` (195); for `REGULAR`, sale = regular |
| Q3 | What values can `channel_restriction` / `age_restriction` take, and should they block adds? | Shown on each line, not enforced | All items are `MULTI-CHANNEL` with age 0 |
| Q4 | Are all prices in USD? | Yes (`cart.currency`, configurable) | The API has no currency field |
| Q5 | Does an Availability 404 mean "no such item" or "no availability data"? | Treated as unavailable (409); the Item service decides whether an item exists (422) | Every catalog item has availability data |

Other assumptions: guest carts only, no authentication (the random UUID cart id acts as the key);
carts expire 30 days after the last change, or 24 hours once empty.

---

## 9. Not included / before production

Deliberately out of scope for the case study, with the intended approach:

| Area | Status | Plan |
|---|---|---|
| **Expired data clean-up** | Expired carts already behave as missing (404), but their rows remain | Scheduled job every 15 min deleting expired carts and idempotency keys in batches of 500, using `FOR UPDATE SKIP LOCKED` so several instances can run it safely. Indexes are already in place |
| **Metrics** | Health, Resilience4j and HTTP client metrics available at `/actuator/metrics` | Add a Prometheus endpoint and business metrics (cart write outcomes, degraded views) following team conventions |
| **Tracing export** | Trace ids are in every log line and sent to the product services (`traceparent`) | Configure an OpenTelemetry exporter to the tracing backend |
| **Authentication** | None; the cart id is an unguessable UUID | OAuth2/JWT, cart bound to the guest's identity; rate limiting at the gateway |
| **Idempotency edge case** | The cart write and the "key completed" record are two transactions; a crash between them could let a retry apply an add twice | Write both in one transaction, or make the cart update conditional on the request id |
| **Load testing** | Not done | k6/Gatling against the parallel cart view; tune the DB pool size |
| **Scale** | PostgreSQL behind a repository interface | A partitioned store (e.g. Cassandra by cart id) at Target scale; only the adapter changes |

---

## 10. Troubleshooting

| Problem | Fix |
|---|---|
| Tests fail with *"Could not find a valid Docker environment"* | Start Docker Desktop and wait until it says it's running |
| `docker compose up` fails with *"port is already allocated"* | Something else uses 8080, 8081 or 5432 (often a local PostgreSQL). Stop it, or change the left-hand port in `docker-compose.yml` |
| App fails at startup with *"Connection refused"* to the database | Start the database first: `docker compose up -d postgres product` |
| Cart view shows `totalIncomplete: true` and `AVAILABILITY_UNKNOWN` | The product services aren't reachable: check `curl http://localhost:8080/product/v1/health` |
| Build fails at `spotlessJavaCheck` | Formatting differs: run `./gradlew spotlessApply` |
| Build fails at `jacocoTestCoverageVerification` | Coverage dropped below 90% lines / 80% branches: add tests |
| `./gradlew: Permission denied` (macOS/Linux) | `chmod +x gradlew scripts/smoke.sh` |
| Behind a corporate proxy, downloads fail | Configure the proxy (or internal mirror) for Gradle in `~/.gradle/gradle.properties` and for Docker in Docker Desktop settings |
| `scripts/smoke.sh` on Windows: *"command not found"* | Run it from Git Bash: `bash scripts/smoke.sh` |
