package com.target.retailcart.app.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.target.retailcart.app.domain.Cart;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.CartLine;
import com.target.retailcart.app.domain.ItemId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalInt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs against a real PostgreSQL (Testcontainers) with the Flyway schema. Tests are not wrapped in
 * a transaction, so each save commits exactly as it does in production and optimistic-lock
 * conflicts surface for real.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(JpaCartRepository.class)
@Testcontainers
class JpaCartRepositoryTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");
  private static final Instant T1 = T0.plus(1, ChronoUnit.MINUTES);
  private static final ItemId TEE = ItemId.of("54276335");
  private static final ItemId CLEANER = ItemId.of("15744221");

  @Autowired JpaCartRepository repository;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void cleanTables() {
    jdbc.update("delete from cart");
  }

  @Test
  void savesAndLoadsANewCart() {
    Cart cart = Cart.create(CartId.newId(), T0);
    cart.addItem(TEE, 2, 20, T0);
    cart.addItem(CLEANER, 1, 20, T0);

    repository.save(cart);
    Cart loaded = repository.findById(cart.id()).orElseThrow();

    assertThat(loaded.lines())
        .extracting(CartLine::itemId, CartLine::quantity)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(TEE, 2),
            org.assertj.core.groups.Tuple.tuple(CLEANER, 1));
    assertThat(loaded.version()).isZero();
    assertThat(loaded.updatedAt()).isEqualTo(T0);
  }

  @Test
  void persistsUpdatesAddsAndRemovalsAndBumpsVersion() {
    CartId id = savedCartWithTee(2);

    Cart cart = repository.findById(id).orElseThrow();
    cart.updateQuantity(TEE, 5, OptionalInt.of(20), T1);
    cart.addItem(CLEANER, 1, 20, T1);
    repository.save(cart);

    Cart afterUpdate = repository.findById(id).orElseThrow();
    assertThat(afterUpdate.line(TEE)).get().extracting(CartLine::quantity).isEqualTo(5);
    assertThat(afterUpdate.lines()).hasSize(2);
    assertThat(afterUpdate.version()).isEqualTo(1L);
    assertThat(afterUpdate.updatedAt()).isEqualTo(T1);

    afterUpdate.removeItem(TEE, T1);
    repository.save(afterUpdate);

    assertThat(repository.findById(id).orElseThrow().lines())
        .extracting(CartLine::itemId)
        .containsExactly(CLEANER);
  }

  @Test
  void emptiedCartIsKept() {
    CartId id = savedCartWithTee(1);

    Cart cart = repository.findById(id).orElseThrow();
    cart.removeItem(TEE, T1);
    repository.save(cart);

    assertThat(repository.findById(id)).get().satisfies(c -> assertThat(c.isEmpty()).isTrue());
    assertThat(jdbc.queryForObject("select count(*) from cart_item", Integer.class)).isZero();
  }

  @Test
  void concurrentWritersConflict() {
    CartId id = savedCartWithTee(1);
    Cart firstTab = repository.findById(id).orElseThrow();
    Cart secondTab = repository.findById(id).orElseThrow();

    firstTab.updateQuantity(TEE, 3, OptionalInt.of(20), T1);
    repository.save(firstTab);

    secondTab.updateQuantity(TEE, 4, OptionalInt.of(20), T1);
    assertThatThrownBy(() -> repository.save(secondTab))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(repository.findById(id).orElseThrow().line(TEE))
        .get()
        .extracting(CartLine::quantity)
        .as("first writer wins, no lost update")
        .isEqualTo(3);
  }

  @Test
  void lineOnlyChangesStillBumpTheVersion() {
    CartId id = savedCartWithTee(1);

    Cart cart = repository.findById(id).orElseThrow();
    cart.updateQuantity(TEE, 2, OptionalInt.of(20), T0); // same updatedAt as before
    repository.save(cart);

    assertThat(repository.findById(id).orElseThrow().version()).isEqualTo(1L);
  }

  @Test
  void schemaRejectsNonPositiveQuantity() {
    CartId id = savedCartWithTee(1);

    assertThatThrownBy(() ->
            jdbc.update(
                "insert into cart_item (cart_id, item_id, quantity, added_at)"
                    + " values (?, '1', 0, now())",
                id.value()))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void unknownCartIsEmpty() {
    assertThat(repository.findById(CartId.newId())).isEmpty();
  }

  private CartId savedCartWithTee(int quantity) {
    Cart cart = Cart.create(CartId.newId(), T0);
    cart.addItem(TEE, quantity, 20, T0);
    repository.save(cart);
    return cart.id();
  }
}
