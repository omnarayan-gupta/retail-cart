package com.target.retailcart.app.domain.port;

import com.target.retailcart.app.domain.Cart;
import com.target.retailcart.app.domain.CartId;
import java.util.Optional;

/**
 * Port to cart storage. PostgreSQL today (D8); a wide-column store at production scale would only
 * need a new adapter.
 */
public interface CartRepository {

  Optional<Cart> findById(CartId id);

  /**
   * Saves the cart. Implementations must use {@link Cart#version()} for optimistic locking and
   * throw Spring's {@code OptimisticLockingFailureException} on a concurrent modification (D26).
   */
  void save(Cart cart);
}
