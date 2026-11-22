package com.target.retailcart.app.application;

import com.target.retailcart.app.domain.CartId;

/** No such cart, or it has expired (D5, D21). Maps to 404 CART_NOT_FOUND. */
public class CartNotFoundException extends RuntimeException {

  private final CartId cartId;

  public CartNotFoundException(CartId cartId) {
    super("Cart " + cartId + " was not found");
    this.cartId = cartId;
  }

  public CartId cartId() {
    return cartId;
  }
}
