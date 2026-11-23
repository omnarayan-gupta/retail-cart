package com.target.retailcart.app.application;

import com.target.retailcart.app.domain.ItemId;

/** The Item service does not know this item (D31). Maps to 422 ITEM_NOT_FOUND. */
public class ItemNotFoundException extends RuntimeException {

  private final ItemId itemId;

  public ItemNotFoundException(ItemId itemId) {
    super("Item " + itemId + " does not exist");
    this.itemId = itemId;
  }

  public ItemId itemId() {
    return itemId;
  }
}
