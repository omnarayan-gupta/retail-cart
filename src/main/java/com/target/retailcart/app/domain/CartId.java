package com.target.retailcart.app.domain;

import java.util.Objects;
import java.util.UUID;

/** Identity of a cart. A random UUID, so it can't be guessed (see D28). */
public record CartId(UUID value) {

  public CartId {
    Objects.requireNonNull(value, "cart id must not be null");
  }

  public static CartId newId() {
    return new CartId(UUID.randomUUID());
  }

  public static CartId of(String value) {
    return new CartId(UUID.fromString(value));
  }

  @Override
  public String toString() {
    return value.toString();
  }
}
