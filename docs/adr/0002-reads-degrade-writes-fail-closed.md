# 0002. Reads degrade gracefully, writes fail closed

## Context

The cart depends on three services that can be slow or down. Two of the brief's rules pull in
different directions: guests should always be able to see their cart, but only available items
may be added.

## Decision

- **Cart view (read):** always returns 200. A failed lookup affects only its own line: no price →
  `PRICE_UNAVAILABLE`, no availability → `AVAILABILITY_UNKNOWN`, no details → `DETAILS_UNAVAILABLE`.
  Lines that can't be priced or checked are left out of the subtotal and the cart is flagged
  `totalIncomplete: true`, with a warning per line.
- **Add / quantity increase (write):** if availability can't be confirmed, refuse with
  `503 DEPENDENCY_UNAVAILABLE` and `Retry-After`. Don't guess.
- **Health checks** cover the service and its database only, never the product services.

## Alternatives

- **Fail the whole view** when any call fails: one slow service would take the cart page down.
- **Show the last known price** when Pricing is down: violates the latest-price rule and could
  mislead the guest.
- **Allow adds when Availability is down:** violates "only available items can be added".
- **Include product services in health:** an outage would make Kubernetes restart or drain every
  cart instance at once, turning a partial outage into a full one.

## Consequences

- Clients must handle `totalIncomplete` and per-line statuses (documented in the API).
- Adds are unavailable while Availability is down; the circuit breaker makes them fail fast.
- The 409 (checked: not available) and 503 (couldn't check) cases are distinct, so clients know
  whether retrying makes sense.
