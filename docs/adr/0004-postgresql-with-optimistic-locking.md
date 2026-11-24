# 0004. PostgreSQL with optimistic locking; no network calls inside transactions

## Context

Carts need durable storage. The same cart can be changed concurrently (two tabs, a retried
request), and lost updates must not happen. Writes also call the Availability service.

## Decision

- **PostgreSQL** with Flyway migrations: tables `cart` and `cart_item`, a unique
  `(cart_id, item_id)` and `check (quantity > 0)` as a backstop for the domain rules.
- **Optimistic locking:** `cart.version` is checked and incremented on every save, including
  changes that only touch `cart_item` rows. On a conflict the service reloads and reapplies the
  change, up to 3 attempts, then returns `409 CONCURRENT_MODIFICATION`.
- **Transaction boundaries:** remote calls happen before the transaction opens; the transaction
  covers only load, change and save.

## Alternatives

- **MongoDB** (cart as one document): a good fit too; PostgreSQL chosen for transactions that
  later features (checkout, reservations) are likely to need, and team familiarity.
- **Cassandra / DynamoDB:** the likely choice at production retail scale, partitioned by cart id.
  The repository port keeps that change to one adapter.
- **Pessimistic locking** (`select … for update`): holds row locks, and worse, it would be tempting
  to hold them across the Availability call.

## Consequences

- Conflicts on a single cart are rare, so optimistic locking is cheap; when they happen, the retry
  makes them invisible to the guest. Proven by a concurrent end-to-end test.
- Availability could change between the check and the save. The window is milliseconds; the final
  guarantee belongs to checkout / inventory reservation.
