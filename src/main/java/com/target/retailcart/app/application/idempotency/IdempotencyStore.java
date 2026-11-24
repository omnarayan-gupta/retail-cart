package com.target.retailcart.app.application.idempotency;

import com.target.retailcart.app.application.CartItemResult;
import java.time.Instant;

/**
 * Port for storing idempotency keys (D6). Implemented on PostgreSQL; Redis is the usual prod
 * choice.
 */
public interface IdempotencyStore {

  /**
   * Atomically claims {@code key} for a new request, or reports who already has it. A completed key
   * older than the TTL counts as absent; an in-progress claim older than the lease counts as
   * abandoned and can be taken over by a request with the same fingerprint.
   */
  Claim claim(String key, String fingerprint, Instant now, IdempotencySettings settings);

  /** Records the successful result so repeats can replay it. */
  void complete(String key, CartItemResult result);

  /** Drops an in-progress claim after a failure, so the client's retry runs again. */
  void release(String key);

  /** Outcome of {@link #claim}. */
  sealed interface Claim {

    /** This request owns the key and should run. */
    record Claimed() implements Claim {}

    /** A request with this key already succeeded. */
    record Completed(String fingerprint, CartItemResult result) implements Claim {}

    /** A request with this key is still running. */
    record InProgress(String fingerprint) implements Claim {}
  }
}
