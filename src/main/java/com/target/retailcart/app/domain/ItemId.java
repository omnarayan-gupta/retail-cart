package com.target.retailcart.app.domain;

/**
 * Identity of a catalog item, as issued by the Item service (numeric string, e.g. "54276335").
 * Format checks for incoming requests happen at the API boundary; the domain only guards against
 * blank or oversized values.
 */
public record ItemId(String value) {

  public static final int MAX_LENGTH = 32;

  public ItemId {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("item id must not be blank");
    }
    if (value.length() > MAX_LENGTH) {
      throw new IllegalArgumentException("item id must be at most " + MAX_LENGTH + " characters");
    }
  }

  public static ItemId of(String value) {
    return new ItemId(value);
  }

  @Override
  public String toString() {
    return value;
  }
}
