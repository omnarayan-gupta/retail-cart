# 0003. Ports and adapters with a plain-Java domain

## Context

The two things most likely to change are the storage technology (PostgreSQL here; likely a
partitioned store at Target scale) and the product service contracts. The business rules should
not change when either does.

## Decision

Four packages: `api`, `application`, `domain` (with `domain.port`), `infrastructure`.

- The `Cart` aggregate is plain Java and owns the rules: one line per item (adds merge), quantity
  at least 1 and within the purchase limit, changes only to items in the cart, expiry.
- The domain declares what it needs as interfaces (`CartRepository`, `ItemClient`, `PricingClient`,
  `AvailabilityClient`); infrastructure implements them.
- JPA entities are separate from the domain model and mapped in the repository adapter.
- Kept pragmatic: one Gradle module, Spring annotations allowed in `application`, no extra
  architecture tooling.

## Alternatives

- **Controller → service → repository with JPA entities as the model:** fewer classes, but rules
  spread across services and the database leaks into the domain.
- **Full hexagonal with a module per layer:** more structure than this service needs.

## Consequences

- Business rules are tested with plain JUnit in milliseconds (`CartTest`).
- Swapping the database or a product API means replacing one adapter.
- Cost: separate entity classes and about 30 lines of mapping.
