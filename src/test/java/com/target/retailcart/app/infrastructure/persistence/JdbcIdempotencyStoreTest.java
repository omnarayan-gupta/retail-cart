package com.target.retailcart.app.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.target.retailcart.app.application.CartItemResult;
import com.target.retailcart.app.application.idempotency.IdempotencySettings;
import com.target.retailcart.app.application.idempotency.IdempotencyStore.Claim;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.ItemId;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** The idempotency store on real PostgreSQL (V2 migration), committing like production does. */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JdbcIdempotencyStore.class)
@Testcontainers
class JdbcIdempotencyStoreTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");
  private static final IdempotencySettings SETTINGS =
      new IdempotencySettings(Duration.ofHours(24), Duration.ofSeconds(10));
  private static final String FP = "a".repeat(64);
  private static final CartItemResult RESULT =
      new CartItemResult(CartId.newId(), ItemId.of("54276335"), 2, 3, true);

  @Autowired JdbcIdempotencyStore store;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void cleanTable() {
    jdbc.update("delete from idempotency_key");
  }

  @Test
  void firstClaimWinsAndSecondSeesInProgress() {
    assertThat(store.claim("k", FP, T0, SETTINGS)).isInstanceOf(Claim.Claimed.class);
    assertThat(store.claim("k", FP, T0.plusSeconds(1), SETTINGS))
        .isEqualTo(new Claim.InProgress(FP));
  }

  @Test
  void completedKeyReturnsTheStoredResult() {
    store.claim("k", FP, T0, SETTINGS);
    store.complete("k", RESULT);

    assertThat(store.claim("k", FP, T0.plusSeconds(60), SETTINGS))
        .isEqualTo(new Claim.Completed(FP, RESULT));
  }

  @Test
  void releasedKeyCanBeClaimedAgain() {
    store.claim("k", FP, T0, SETTINGS);
    store.release("k");

    assertThat(store.claim("k", FP, T0.plusSeconds(1), SETTINGS)).isInstanceOf(Claim.Claimed.class);
  }

  @Test
  void abandonedClaimIsTakenOverAfterTheLease() {
    store.claim("k", FP, T0, SETTINGS);

    assertThat(store.claim("k", FP, T0.plusSeconds(9), SETTINGS))
        .isInstanceOf(Claim.InProgress.class);
    assertThat(store.claim("k", FP, T0.plusSeconds(10), SETTINGS))
        .isInstanceOf(Claim.Claimed.class);
    assertThat(store.claim("k", FP, T0.plusSeconds(11), SETTINGS))
        .as("the new owner now holds a fresh lease")
        .isInstanceOf(Claim.InProgress.class);
  }

  @Test
  void abandonedClaimIsNotTakenOverByADifferentRequest() {
    store.claim("k", FP, T0, SETTINGS);

    assertThat(store.claim("k", "b".repeat(64), T0.plusSeconds(30), SETTINGS))
        .isEqualTo(new Claim.InProgress(FP));
  }

  @Test
  void completedKeyIsForgottenAfterTheTtl() {
    store.claim("k", FP, T0, SETTINGS);
    store.complete("k", RESULT);

    assertThat(store.claim("k", FP, T0.plus(Duration.ofHours(24)), SETTINGS))
        .isInstanceOf(Claim.Claimed.class);
  }

  @Test
  void concurrentClaimsForTheSameKeyHaveExactlyOneWinner() throws Exception {
    int requests = 8;
    var ready = new CountDownLatch(requests);
    List<Callable<Claim>> tasks = new ArrayList<>();
    for (int i = 0; i < requests; i++) {
      tasks.add(() -> {
        ready.countDown();
        ready.await();
        return store.claim("k", FP, T0, SETTINGS);
      });
    }

    List<Claim> claims = new ArrayList<>();
    try (var executor = Executors.newFixedThreadPool(requests)) {
      for (Future<Claim> f : executor.invokeAll(tasks)) {
        claims.add(f.get());
      }
    }

    assertThat(claims).filteredOn(Claim.Claimed.class::isInstance).hasSize(1);
    assertThat(claims).filteredOn(Claim.InProgress.class::isInstance).hasSize(requests - 1);
  }
}
