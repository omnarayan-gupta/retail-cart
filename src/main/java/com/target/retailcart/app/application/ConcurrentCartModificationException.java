package com.target.retailcart.app.application;

import com.target.retailcart.app.domain.CartId;

/**
 * The cart kept changing underneath us and retries were exhausted (D26). Maps to 409
 * CONCURRENT_MODIFICATION; the client can simply retry.
 */
public class ConcurrentCartModificationException extends RuntimeException {

  public ConcurrentCartModificationException(CartId cartId, Throwable cause) {
    super("Cart " + cartId + " was modified concurrently; please retry", cause);
  }
}
