package com.target.retailcart.app.domain;

/** The item is not in the cart (update or remove of a missing line). Maps to 404. */
public class ItemNotInCartException extends CartDomainException {

  private final ItemId itemId;

  public ItemNotInCartException(ItemId itemId) {
    super("Item " + itemId + " is not in the cart");
    this.itemId = itemId;
  }

  public ItemId itemId() {
    return itemId;
  }
}
