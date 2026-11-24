package com.target.retailcart.app.application.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.target.retailcart.app.application.CartItemResult;
import com.target.retailcart.app.application.idempotency.IdempotencyStore.Claim;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.port.DependencyUnavailableException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class IdempotencyServiceTest {

  private static final CartItemResult RESULT =
      new CartItemResult(CartId.newId(), ItemId.of("54276335"), 2, 2, true);
  private static final String ADD_TEE = "POST /api/v1/carts/items 54276335 x2";

  private final InMemoryStore store = new InMemoryStore();
  private final IdempotencyService service =
      new IdempotencyService(
          store,
          new IdempotencySettings(Duration.ofHours(24), Duration.ofSeconds(10)),
          Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC));
  private final AtomicInteger executions = new AtomicInteger();

  private Supplier<CartItemResult> addTee() {
    return () -> {
      executions.incrementAndGet();
      return RESULT;
    };
  }

  @Test
  void withoutKeyEveryRequestRuns() {
    service.execute(null, ADD_TEE, addTee());
    service.execute(null, ADD_TEE, addTee());

    assertThat(executions).hasValue(2);
    assertThat(store.rows).isEmpty();
  }

  @Test
  void firstRequestRunsAndIsRemembered() {
    IdempotentOutcome outcome = service.execute("key-1", ADD_TEE, addTee());

    assertThat(outcome).isEqualTo(new IdempotentOutcome(RESULT, false));
    assertThat(store.rows.get("key-1")).isInstanceOf(Claim.Completed.class);
  }

  @Test
  void repeatReplaysTheOriginalResultWithoutRunning() {
    service.execute("key-1", ADD_TEE, addTee());

    IdempotentOutcome repeat = service.execute("key-1", ADD_TEE, addTee());

    assertThat(repeat).isEqualTo(new IdempotentOutcome(RESULT, true));
    assertThat(executions).as("the add ran once, not twice").hasValue(1);
  }

  @Test
  void sameKeyForADifferentRequestIsRejected() {
    service.execute("key-1", ADD_TEE, addTee());

    assertThatThrownBy(
            () -> service.execute("key-1", "POST /api/v1/carts/items 54276335 x5", addTee()))
        .isInstanceOf(IdempotencyKeyReusedException.class);
    assertThat(executions).hasValue(1);
  }

  @Test
  void requestStillRunningMakesTheRepeatWait() {
    store.rows.put("key-1", new Claim.InProgress(IdempotencyService.sha256(ADD_TEE)));

    assertThatThrownBy(() -> service.execute("key-1", ADD_TEE, addTee()))
        .isInstanceOf(IdempotencyKeyInProgressException.class);
    assertThat(executions).hasValue(0);
  }

  @Test
  void failureIsNotRememberedSoTheRetryRunsAgain() {
    assertThatThrownBy(() ->
            service.execute(
                "key-1",
                ADD_TEE,
                () -> {
                  throw new DependencyUnavailableException("availability", "down", null);
                }))
        .isInstanceOf(DependencyUnavailableException.class);
    assertThat(store.rows).as("claim released").isEmpty();

    IdempotentOutcome retry = service.execute("key-1", ADD_TEE, addTee());

    assertThat(retry.replayed()).isFalse();
    assertThat(executions).hasValue(1);
  }

  @Test
  void fingerprintIsAStableSha256() {
    assertThat(IdempotencyService.sha256(ADD_TEE))
        .hasSize(64)
        .isEqualTo(IdempotencyService.sha256(ADD_TEE))
        .isNotEqualTo(IdempotencyService.sha256(ADD_TEE + " "));
  }

  /** Minimal store: claim semantics without TTL or lease (those are covered in the JDBC test). */
  private static final class InMemoryStore implements IdempotencyStore {
    final Map<String, Claim> rows = new HashMap<>();

    @Override
    public Claim claim(String key, String fingerprint, Instant now, IdempotencySettings settings) {
      Claim existing = rows.putIfAbsent(key, new Claim.InProgress(fingerprint));
      return existing == null ? new Claim.Claimed() : existing;
    }

    @Override
    public void complete(String key, CartItemResult result) {
      Claim.InProgress claim = (Claim.InProgress) rows.get(key);
      rows.put(key, new Claim.Completed(claim.fingerprint(), result));
    }

    @Override
    public void release(String key) {
      rows.remove(key);
    }
  }
}
