package com.target.retailcart.app.domain;

/**
 * The requested total quantity for an item is above what may be added (D19/D29). Maps to 409 and
 * tells the client the current maximum, so it can offer "reduce to N".
 */
public class QuantityLimitExceededException extends CartDomainException {

  private final ItemId itemId;
  private final int requestedQuantity;
  private final int maxAllowed;

  public QuantityLimitExceededException(ItemId itemId, int requestedQuantity, int maxAllowed) {
    super(
        "Requested quantity "
            + requestedQuantity
            + " for item "
            + itemId
            + " exceeds the maximum of "
            + maxAllowed);
    this.itemId = itemId;
    this.requestedQuantity = requestedQuantity;
    this.maxAllowed = maxAllowed;
  }

  public ItemId itemId() {
    return itemId;
  }

  public int requestedQuantity() {
    return requestedQuantity;
  }

  public int maxAllowed() {
    return maxAllowed;
  }
}
