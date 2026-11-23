package com.target.retailcart.app.application;

import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.ItemId;

/**
 * Lightweight result of a write (D17): the affected line and the cart's unit count. No prices;
 * clients call GET /carts/{id} when they render the cart.
 *
 * @param cartCreated true when this write created the cart (first add, D3)
 */
public record CartItemResult(
    CartId cartId, ItemId itemId, int quantity, int itemCount, boolean cartCreated) {}
