package com.target.retailcart.app.domain;

/** Base type for violations of cart business rules. The API layer maps each subtype to a status. */
public abstract class CartDomainException extends RuntimeException {

  protected CartDomainException(String message) {
    super(message);
  }
}
