package com.target.retailcart.app.application.idempotency;

import com.target.retailcart.app.application.CartItemResult;
import com.target.retailcart.app.application.idempotency.IdempotencyStore.Claim;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Makes cart adds safe to retry (D6). A client that timed out can resend the same request with the
 * same {@code Idempotency-Key} and gets the original result instead of a second cart or a doubled
 * quantity.
 *
 * <ul>
 *   <li>No key: the request just runs (the header is optional).
 *   <li>New key: claim it, run, store the result.
 *   <li>Completed key, same request: replay the stored result; nothing runs.
 *   <li>Same key, different request: {@link IdempotencyKeyReusedException} (422).
 *   <li>Key still running: {@link IdempotencyKeyInProgressException} (409).
 *   <li>Only successes are stored. A failed request changed nothing, so its claim is released and a
 *       retry runs again with fresh availability.
 * </ul>
 *
 * <p><b>Known gap</b>: the cart save and {@link IdempotencyStore#complete} are separate
 * transactions. If the process dies between them, a retry after the lease expires runs the add
 * again. Closing it means writing both in one transaction; see the design notes.
 */
@Service
public class IdempotencyService {

  private final IdempotencyStore store;
  private final IdempotencySettings settings;
  private final Clock clock;

  public IdempotencyService(IdempotencyStore store, IdempotencySettings settings, Clock clock) {
    this.store = store;
    this.settings = settings;
    this.clock = clock;
  }

  /**
   * @param key the client's Idempotency-Key, or null when none was sent
   * @param request a description of the request (method, path, body values); requests are the same
   *     when their descriptions are equal
   */
  public IdempotentOutcome execute(
      @Nullable String key, String request, Supplier<CartItemResult> action) {
    if (key == null) {
      return new IdempotentOutcome(action.get(), false);
    }
    String fingerprint = sha256(request);
    Claim claim = store.claim(key, fingerprint, clock.instant(), settings);
    return switch (claim) {
      case Claim.Claimed claimed -> run(key, action);
      case Claim.Completed completed -> {
        requireSameRequest(completed.fingerprint(), fingerprint);
        yield new IdempotentOutcome(completed.result(), true);
      }
      case Claim.InProgress inProgress -> {
        requireSameRequest(inProgress.fingerprint(), fingerprint);
        throw new IdempotencyKeyInProgressException();
      }
    };
  }

  private IdempotentOutcome run(String key, Supplier<CartItemResult> action) {
    CartItemResult result;
    try {
      result = action.get();
    } catch (RuntimeException e) {
      try {
        store.release(key);
      } catch (RuntimeException releaseFailure) {
        e.addSuppressed(releaseFailure); // the lease expires anyway; report the real failure
      }
      throw e;
    }
    store.complete(key, result);
    return new IdempotentOutcome(result, false);
  }

  private static void requireSameRequest(String stored, String current) {
    if (!stored.equals(current)) {
      throw new IdempotencyKeyReusedException();
    }
  }

  static String sha256(String text) {
    try {
      byte[] hash =
          MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is always available", e);
    }
  }
}
