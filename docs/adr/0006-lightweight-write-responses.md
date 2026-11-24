# 0006. Writes return a small summary; only GET returns prices

## Context

A write could return the full priced cart. That would put Pricing and Item calls for every line on
the write path.

## Decision

Writes return `{cartId, itemId, quantity, itemCount}` (DELETE returns 204). Only
`GET /carts/{cartId}` builds the priced view. Adds call Item and Availability only; quantity
increases call Availability only; decreases and removals call nothing.

## Alternatives

- **Return the full priced cart from every write:** one call fewer for the client, but every write
  then depends on Pricing and pays for a full fan-out.

## Consequences

- A Pricing outage never blocks adding to the cart.
- Writes stay fast and their failure modes stay simple.
- Clients call GET when they render the cart. If a client needs the priced cart right after a write,
  an opt-in (e.g. `?view=full`) can be added without changing the default.
