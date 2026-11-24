# 0001. Store only item ids and quantities; fetch price and availability live

## Context

The brief says availability and pricing can change in real time, and the cart must always reflect
the latest price. Any price or availability stored in the cart is out of date the moment it changes
upstream. Item details (name, image) change rarely, but are owned by the Item service too.

## Decision

A cart line stores only `itemId`, `quantity` and `addedAt`. Every cart view fetches item details,
price and availability for every line from the product services, in parallel. No price is ever
cached or used as a fallback; if Pricing can't answer, the line shows no price (see 0002).

## Alternatives

- **Snapshot price and details at add time, refresh on view.** Two sources of truth, and the
  stored value is wrong whenever it differs.
- **Cache item details with a TTL.** Reasonable later for details only (they change rarely); never
  for price.

## Consequences

- The latest-price requirement holds by construction: there is nothing stale to show.
- Each view makes 3 calls per line. Running them all in parallel keeps latency close to the slowest
  single call (0008); timeouts and circuit breakers bound it (0009).
- The cart view depends on the product services being up; this is handled by degrading per line.
- At scale: bulk lookup endpoints (none exist today) and an item-details cache are the next steps.
