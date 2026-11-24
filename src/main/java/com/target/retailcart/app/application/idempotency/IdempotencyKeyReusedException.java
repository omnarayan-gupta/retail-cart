package com.target.retailcart.app.application.idempotency;

/** The key was already used for a different request: a client bug, surfaced rather than hidden. */
public class IdempotencyKeyReusedException extends RuntimeException {

  public IdempotencyKeyReusedException() {
    super("This Idempotency-Key was already used for a different request");
  }
}
