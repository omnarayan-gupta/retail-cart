# 0005. The cart is created by the first add

## Context

A guest's first action is adding an item. A separate "create cart" call would cost a round trip
and leave empty carts behind when the add fails.

## Decision

- `POST /api/v1/carts/items` creates the cart and adds the item, returning `201 Created` with
  `Location: /api/v1/carts/{cartId}`.
- `POST /api/v1/carts/{cartId}/items` adds to an existing cart. Both are the same use case.
- An unknown or expired `cartId` is `404 CART_NOT_FOUND`; the client starts a new cart. A new cart
  is never created silently.
- A cart whose last item is removed is kept (empty) so the guest's cart id stays valid.

## Alternatives

- **`POST /carts` then add:** two calls for the most common first action.
- **One endpoint with an optional cart id in the body:** works, but the cart id belongs in the path
  like every other cart operation.
- **Create a new cart when the id is unknown:** hides client bugs and expired carts.

## Consequences

- One round trip for the first add.
- A retried first add could create a second cart; the Idempotency-Key prevents it (0010).
