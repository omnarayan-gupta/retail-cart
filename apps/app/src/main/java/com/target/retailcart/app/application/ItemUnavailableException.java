package com.target.retailcart.app.application;

import com.target.retailcart.app.domain.ItemId;

/**
 * The item can't be ordered right now: no units available, or no availability data (D31). Maps to
 * 409 ITEM_UNAVAILABLE.
 */
public class ItemUnavailableException extends RuntimeException {

  private final ItemId itemId;

  public ItemUnavailableException(ItemId itemId) {
    super("Item " + itemId + " is not available");
    this.itemId = itemId;
  }

  public ItemId itemId() {
    return itemId;
  }
}
