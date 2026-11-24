# 0007. Purchase limit comes from the Availability service

## Context

A fixed quantity limit (say 1–99) ignores stock and per-item limits. The Availability service
returns `available_units` and `limited_quantity_threshold`; the meaning of the threshold isn't
documented (clarifying question Q1).

## Decision

- An item is available when `available_units > 0`.
- Maximum quantity = `min(available_units, limited_quantity_threshold)` when the threshold is
  positive, otherwise `available_units`.
- Adds and increases beyond it are rejected with `409 QUANTITY_LIMIT_EXCEEDED` and `maxAllowed`.
- If the limit later drops below a quantity already in the cart, the line is flagged
  `QUANTITY_EXCEEDS_LIMIT` with the new `maxAllowed`; it is not reduced automatically.

## Evidence

In the product data the threshold is always below stock and follows stock tiers (5–14 units → 2,
23–95 → 5, 102–296 → 10, 312+ → 20), which fits a per-item purchase limit.

## Alternatives

- **Treat the threshold as a "low stock" hint only:** would let a guest add all remaining units of
  a scarce item.
- **Clamp quantities silently when the limit drops:** surprises the guest; flagging lets the client
  offer "reduce to 5".

## Consequences

- Safe under either reading of the field: never more than stock, and never more than the threshold.
- One small policy class (`AvailabilityPolicy`) changes if the answer to Q1 differs.
