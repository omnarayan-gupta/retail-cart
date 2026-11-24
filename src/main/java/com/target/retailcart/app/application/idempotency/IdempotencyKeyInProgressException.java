package com.target.retailcart.app.application.idempotency;

/** An earlier request with the same key is still running; the client should retry shortly. */
public class IdempotencyKeyInProgressException extends RuntimeException {

  public IdempotencyKeyInProgressException() {
    super("A request with this Idempotency-Key is still being processed; retry shortly");
  }
}
