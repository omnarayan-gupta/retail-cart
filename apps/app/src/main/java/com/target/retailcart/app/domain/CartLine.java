package com.target.retailcart.app.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of a cart: which item and how many. Deliberately holds no price or item details, so the
 * cart can never show stale data (D7). Immutable; the {@link Cart} replaces lines when they change.
 */
public record CartLine(ItemId itemId, int quantity, Instant addedAt) {

  public CartLine {
    Objects.requireNonNull(itemId, "itemId must not be null");
    Objects.requireNonNull(addedAt, "addedAt must not be null");
    Cart.requireValidQuantity(quantity);
  }

  CartLine withQuantity(int newQuantity) {
    return new CartLine(itemId, newQuantity, addedAt);
  }
}
