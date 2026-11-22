package com.target.retailcart.app.application;

import com.target.retailcart.app.domain.Cart;
import com.target.retailcart.app.domain.CartExpiryPolicy;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.port.CartRepository;
import java.time.Clock;
import org.springframework.stereotype.Component;

/** Loads carts for reads and writes, treating an expired cart exactly like a missing one (D21). */
@Component
public class ActiveCarts {

  private final CartRepository carts;
  private final CartExpiryPolicy expiryPolicy;
  private final Clock clock;

  public ActiveCarts(CartRepository carts, CartExpiryPolicy expiryPolicy, Clock clock) {
    this.carts = carts;
    this.expiryPolicy = expiryPolicy;
    this.clock = clock;
  }

  /**
   * @throws CartNotFoundException if the cart does not exist or has expired
   */
  public Cart load(CartId cartId) {
    return carts
        .findById(cartId)
        .filter(cart -> !cart.isExpired(clock.instant(), expiryPolicy))
        .orElseThrow(() -> new CartNotFoundException(cartId));
  }
}
