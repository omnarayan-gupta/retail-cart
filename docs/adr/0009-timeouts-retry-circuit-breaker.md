# 0009. Timeouts, one retry, and a circuit breaker per product service

## Context

Without limits, a slow product service would hold every cart request open, and a down one would be
hammered by retries.

## Decision

Per service (Item, Pricing, Availability), using Resilience4j, configured in `application.yml`:

- **Timeouts:** 250 ms to connect, 400 ms to read.
- **Retry:** one extra attempt after 50 ms, for transient failures only (connection errors,
  timeouts, 5xx, unreadable responses). Never for 404 (an answer: "no such item") or other 4xx.
- **Circuit breaker:** opens when 50% of the last 20 calls failed (minimum 10 calls), stays open
  for 10 s, then allows 3 trial calls. 404s don't count as failures.
- **Retry inside the breaker,** so a call and its retry are one outcome.
- Wired explicitly in code (`ResilientCall`) rather than with annotations, so the order is visible
  and there are no proxy surprises.

The worst case per lookup is 400 + 50 + 400 = 850 ms, inside the cart view's 1 s budget.

## Alternatives

- **No retry:** one dropped packet becomes a degraded line or a 503.
- **More retries / longer timeouts:** slower failures and more load on a struggling service.
- **Annotations (`@CircuitBreaker`, `@Retry`):** less code, but ordering is implicit and calls from
  within the same class bypass them.

## Consequences

- While a service is down, requests fail in milliseconds instead of waiting on timeouts, and the
  service gets breathing room to recover; recovery is automatic.
- Breaker state and retry counts are visible in `/actuator/metrics`.

## Note

Spring Framework 7's JDK HTTP client enforces the read timeout over the whole exchange and closes
the stream if it expires during JSON parsing. That surfaces as a generic `RestClientException`, so
the retry rule is "any `RestClientException` except 4xx" rather than a list of specific types. This
was found through an intermittently failing test on a cold start.
