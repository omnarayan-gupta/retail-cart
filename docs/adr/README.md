# Architecture decision records

Short records of the decisions most likely to matter to someone extending this service. The
complete list of decisions, including smaller ones, is in [../DECISIONS.md](../DECISIONS.md).

| # | Decision | Status |
|---|---|---|
| [0001](0001-store-only-item-ids-and-quantities.md) | Store only item ids and quantities; fetch price and availability live | Accepted |
| [0002](0002-reads-degrade-writes-fail-closed.md) | Reads degrade gracefully, writes fail closed | Accepted |
| [0003](0003-ports-and-adapters.md) | Ports and adapters with a plain-Java domain | Accepted |
| [0004](0004-postgresql-with-optimistic-locking.md) | PostgreSQL with optimistic locking; no network calls inside transactions | Accepted |
| [0005](0005-cart-created-on-first-add.md) | The cart is created by the first add | Accepted |
| [0006](0006-lightweight-write-responses.md) | Writes return a small summary; only GET returns prices | Accepted |
| [0007](0007-purchase-limit-from-availability.md) | Purchase limit comes from the Availability service | Accepted (pending Q1) |
| [0008](0008-virtual-threads-for-parallel-lookups.md) | RestClient and virtual threads for parallel lookups | Accepted |
| [0009](0009-timeouts-retry-circuit-breaker.md) | Timeouts, one retry, and a circuit breaker per product service | Accepted |
| [0010](0010-idempotency-key.md) | Optional Idempotency-Key on adds | Accepted, known gap |
| [0011](0011-cart-expiry.md) | Carts expire after inactivity; clean-up job deferred | Accepted |
