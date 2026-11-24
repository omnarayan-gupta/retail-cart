# 0008. RestClient and virtual threads for parallel lookups

## Context

A cart view needs 3 calls per line and the product services have no bulk endpoints. Made one after
another, a 10-line cart would take 30 round trips.

## Decision

- HTTP calls use Spring's blocking `RestClient`.
- The view starts every lookup for every line at once with `CompletableFuture` on a
  virtual-thread-per-task executor, then joins them. Each lookup is capped at 1 s.
- Request handling also runs on virtual threads (`spring.threads.virtual.enabled`).
- The executor carries the caller's context to each task, so the calls stay in the request's trace.

## Alternatives

- **Sequential calls:** latency grows with cart size.
- **A tuned platform thread pool:** works, but needs sizing and can be exhausted by slow calls.
- **WebClient / WebFlux:** non-blocking, but brings a reactive programming model into a Spring MVC
  application for no benefit once virtual threads are available.
- **OpenFeign:** an extra dependency for what RestClient already does.

## Consequences

- View latency is close to the slowest single call rather than the sum.
- Blocking code stays simple to read, debug and test.
- Virtual threads remove the thread limit, not downstream limits: the product services and the
  database pool still cap throughput, which is why every call has a timeout and transactions are
  short.
