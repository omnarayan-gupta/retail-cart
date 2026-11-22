package com.target.retailcart.app.domain;

import java.time.Duration;
import java.util.Objects;

/**
 * How long an inactive cart lives (D21). Carts with items live longer than empty carts, which only
 * exist after the last item was removed and carry little value.
 *
 * @param ttl lifetime of a cart with items, counted from its last write (default 30 days)
 * @param emptyTtl lifetime of an empty cart, counted from its last write (default 24 hours)
 */
public record CartExpiryPolicy(Duration ttl, Duration emptyTtl) {

  public CartExpiryPolicy {
    Objects.requireNonNull(ttl, "ttl must not be null");
    Objects.requireNonNull(emptyTtl, "emptyTtl must not be null");
    if (ttl.isNegative() || ttl.isZero() || emptyTtl.isNegative() || emptyTtl.isZero()) {
      throw new IllegalArgumentException("cart TTLs must be positive");
    }
  }

  public static CartExpiryPolicy defaults() {
    return new CartExpiryPolicy(Duration.ofDays(30), Duration.ofHours(24));
  }
}
