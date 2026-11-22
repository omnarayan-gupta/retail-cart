package com.target.retailcart.app.api.dto;

import com.target.retailcart.app.application.CartItemResult;

/** Lightweight write response (D17): the changed line plus the cart's total unit count. */
public record CartItemResponse(String cartId, String itemId, int quantity, int itemCount) {

  public static CartItemResponse from(CartItemResult result) {
    return new CartItemResponse(
        result.cartId().toString(), result.itemId().value(), result.quantity(), result.itemCount());
  }
}
