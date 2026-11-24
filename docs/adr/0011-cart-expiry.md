# 0011. Carts expire after inactivity; clean-up job deferred

## Context

Guest carts are abandoned all the time. Keeping them forever wastes storage, but a cart id must not
stop working in the middle of a shopping session.

## Decision

- A cart with items expires **30 days** after its last change; an empty cart **24 hours** after its
  last change (empty carts only exist after the last item is removed). Views don't extend the
  lifetime; writes do.
- An expired cart behaves exactly like a missing one (`404 CART_NOT_FOUND`), checked when the cart
  is loaded. This is in place and tested.
- **Deleting expired rows is deferred** (not needed for correctness). Planned design: a scheduled
  job every 15 minutes deleting expired carts and idempotency keys in batches of 500 with
  `FOR UPDATE SKIP LOCKED`, so several instances can run it at once without a distributed lock. The
  supporting indexes (`cart.updated_at`, `idempotency_key.created_at`) already exist.

## Alternatives

- **Delete carts as soon as they're empty:** the guest's next add to that cart id would get a 404.
- **Reads extend the lifetime:** a cart viewed once a month would never expire.
- **ShedLock for the job:** another dependency; unnecessary when the delete is safe to run
  concurrently.

## Consequences

- Behaviour is correct today; storage grows until the clean-up job exists (a pre-production task).
- In a store with native TTL (Cassandra, Redis, DynamoDB) the job would not be needed.
