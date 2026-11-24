# 0010. Optional Idempotency-Key on adds

## Context

A client that times out doesn't know whether its add succeeded. Retrying a first add would create a
second cart; retrying an add to an existing cart would double the quantity. (`PATCH` sets an
absolute quantity and `DELETE` removes, so both are already safe to repeat.)

## Decision

Both `POST` add endpoints accept an optional `Idempotency-Key` header.

- A request claims its key with `insert … on conflict do nothing` in an `idempotency_key` table;
  the primary key guarantees one winner among concurrent duplicates.
- Same key, same request (SHA-256 of method, path and body values): the stored result is returned
  with `Idempotent-Replayed: true`; nothing runs.
- Same key, different request: `422 IDEMPOTENCY_KEY_REUSED`.
- First request still running: `409 IDEMPOTENCY_KEY_IN_PROGRESS` with `Retry-After: 1`.
- Only successes are stored. A failed request changed nothing, so its claim is released and a retry
  runs again with fresh availability.
- Keys are remembered for 24 hours. A claim older than 10 s is treated as abandoned and can be taken
  over by the same request.

## Alternatives

- **Redis** for keys: the usual production choice; PostgreSQL avoids new infrastructure here and is
  shared by all instances.
- **Store failures too:** a retry would replay a stale "out of stock" or "service unavailable".
- **A servlet filter that caches raw responses:** more generic, but hides the logic and stores
  more than needed.

## Consequences

- Clients can retry adds safely; clients that don't send the header are unaffected.
- **Known gap:** the cart save and marking the key completed are separate transactions. If the
  process dies between them, a retry after the 10 s lease runs the add again. Options to close it:
  write both in one transaction, or record the request id on the cart change itself so a repeat is
  detected there.
