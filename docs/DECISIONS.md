# Decision log

Every design decision, in the order it was made. Later decisions that refine or replace earlier
ones say so. The most important ones have a fuller write-up in [adr/](adr/README.md).

## Scope and process

| # | Decision | Why |
|---|---|---|
| D1 | REST API only (Spring Web MVC), no UI | The brief asks for RESTful endpoints; any web or mobile client can use them |
| D2 | Design spec first, then build in small commits | Gives a reviewable record of how the solution evolved |
| D32 | Unit and integration tests (JUnit, Testcontainers, WireMock); end-to-end smoke script against the real product container; load testing out of scope | The real container can't change prices or fail on demand, so the rules are proven with deterministic fakes; the real container proves the contract |
| D35 | Deliverables include the git history, README, architecture notes and ADRs | The history shows how the design evolved |

## API

| # | Decision | Why |
|---|---|---|
| D3 | The first add creates the cart; no separate "create cart" call | One round trip; no empty carts from failed adds ([ADR 0005](adr/0005-cart-created-on-first-add.md)) |
| D4 | `POST /carts/items` (new cart, 201 + Location) and `POST /carts/{cartId}/items` (existing cart) | The cart id stays in the path like every other cart operation |
| D5 | Unknown or expired cart id → 404 `CART_NOT_FOUND` | Don't hide client bugs or expiry by silently creating a cart |
| D9 | Adding an item already in the cart increases its quantity (one line per item) | Matches retail behaviour; enforced by a unique constraint too |
| D16 | Versioned base path `/api/v1`; errors are RFC 7807 Problem Details with a machine-readable `code` | Consistent, evolvable error contract |
| D17 | Writes return `{cartId, itemId, quantity, itemCount}`; DELETE returns 204; only GET returns prices | Keeps Pricing off the write path ([ADR 0006](adr/0006-lightweight-write-responses.md)) |
| D18 | PATCH sets an absolute quantity (≥ 1); removal is DELETE only | Idempotent and unambiguous |
| D6 / D39 | Optional `Idempotency-Key` on both adds, stored in PostgreSQL; replay on repeat, 422 on reuse with a different request, 409 while in progress; only successes stored | Safe client retries ([ADR 0010](adr/0010-idempotency-key.md)) |

## Business rules

| # | Decision | Why |
|---|---|---|
| D7 | Store only item id, quantity and when it was added; fetch details, price and availability live on every view | The cart can never show a stale price ([ADR 0001](adr/0001-store-only-item-ids-and-quantities.md)) |
| D10 | An item that becomes unavailable stays in the cart, flagged `UNAVAILABLE` and excluded from the subtotal | Show what changed rather than silently removing it |
| D11 | Reads degrade per line; writes fail closed with 503 | ([ADR 0002](adr/0002-reads-degrade-writes-fail-closed.md)) |
| D19 / D29 | Available ⇔ `available_units > 0`; max quantity = min(`available_units`, `limited_quantity_threshold`) when the threshold is positive | Business limits owned by the Availability service ([ADR 0007](adr/0007-purchase-limit-from-availability.md)); open question Q1 |
| D22 | If the limit drops below the cart quantity, flag `QUANTITY_EXCEEDS_LIMIT`; don't reduce automatically | Consistent with D10 |
| D30 | `SALE` → sale price, otherwise regular; currency USD (configurable); `BigDecimal` throughout | Follows the service's own signal; open questions Q2, Q4 |
| D31 | Add calls Item and Availability in parallel: unknown item → 422, unavailable → 409, over the limit → 409 with `maxAllowed`. Increases call Availability only. Channel/age restrictions shown, not enforced | Distinguishes "doesn't exist" from "not available" at no extra latency; open question Q3 |
| D20 / D21 | Empty carts are kept; carts expire 30 days after the last write, empty carts after 24 hours; expired carts behave as missing | Stable cart id during a session without keeping junk forever ([ADR 0011](adr/0011-cart-expiry.md)) |
| D40 | Expired-row clean-up job designed but not built; a pre-production task | Behaviour is already correct; only storage is affected |

## Architecture and persistence

| # | Decision | Why |
|---|---|---|
| D23 | Ports and adapters: `api`, `application`, `domain` (+ `port`), `infrastructure` | Storage and product contracts can change behind interfaces ([ADR 0003](adr/0003-ports-and-adapters.md)) |
| D24 | Rules in the `Cart` aggregate (plain Java); services orchestrate | Rules tested in isolation |
| D25 | Domain model separate from JPA entities | No persistence concerns in the domain |
| D8 | PostgreSQL with Spring Data JPA and Flyway | Transactions for likely next features; a partitioned store is the scale-out path ([ADR 0004](adr/0004-postgresql-with-optimistic-locking.md)) |
| D26 | Optimistic locking on the cart row, retried up to 3 times, then 409 | No lost updates without holding locks |
| D26a | Remote calls happen before the transaction; the transaction covers only load, change, save | Never hold a connection or lock during a network call |
| D28 | No authentication in the case study; random UUID cart ids; validated input; no internals in errors; secrets from the environment | Out of scope for the brief; production path documented in the README |

## Calling the product services

| # | Decision | Why |
|---|---|---|
| D13 | Spring `RestClient`, one adapter per product service behind a port | Modern blocking client that fits Spring MVC |
| D14 | Parallel lookups with `CompletableFuture` on virtual threads | Latency close to the slowest call ([ADR 0008](adr/0008-virtual-threads-for-parallel-lookups.md)) |
| D15 / D38 | Connect 250 ms, read 400 ms; one retry after 50 ms for transient errors (never 4xx); a circuit breaker per service; wired explicitly; HTML entities in names cleaned in the adapter | Bounded latency, protection for struggling services ([ADR 0009](adr/0009-timeouts-retry-circuit-breaker.md)) |

## Operations

| # | Decision | Why |
|---|---|---|
| D27 / D41 | Health covers only the service and its database. Built: JSON logs in Docker, trace ids in logs, `traceparent` propagation (including from virtual threads). Prometheus and business metrics left to team conventions | Dependency outages must not trigger restarts; requests can be traced end to end |
| D34 | `docker compose up` runs PostgreSQL, the product services and the cart; multi-stage Dockerfile, non-root, layered | One command for reviewers |

## Build

| # | Decision | Why |
|---|---|---|
| D12 | Java 21 and Spring Boot 4.2 (Boot 3.x is no longer offered for new projects) | Current supported versions; virtual threads |
| D33 | Gradle with the wrapper committed | Reproducible builds |
| D36 | Kotlin DSL, version catalog, Spotless, Makefile, aligned with the team's project template | Familiar conventions |
| D37 | Single Gradle module (amends D36's two modules) | The team convention is one build per service; ports already provide the isolation |
| | Coverage gate: build fails below 90% lines / 80% branches | Keeps untested code from creeping in |
