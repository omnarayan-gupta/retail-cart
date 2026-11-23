package com.target.retailcart.app.application;

import com.target.retailcart.app.domain.Cart;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.port.CartRepository;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.OptimisticLockingFailureException;

/**
 * Test fake that behaves like the real adapter: stores copies, bumps the version on save, and
 * rejects stale versions. {@link #simulateConcurrentWrites(int)} makes the next N saves conflict.
 */
class InMemoryCartRepository implements CartRepository {

  private final Map<CartId, Cart> store = new HashMap<>();
  private int conflictsToSimulate;
  int saveAttempts;

  @Override
  public Optional<Cart> findById(CartId id) {
    return Optional.ofNullable(store.get(id)).map(InMemoryCartRepository::copy);
  }

  @Override
  public void save(Cart cart) {
    saveAttempts++;
    if (conflictsToSimulate > 0) {
      conflictsToSimulate--;
      throw new OptimisticLockingFailureException("simulated concurrent write");
    }
    Cart stored = store.get(cart.id());
    Long storedVersion = stored == null ? null : stored.version();
    if (!Objects.equals(storedVersion, cart.version())) {
      throw new OptimisticLockingFailureException("stale version");
    }
    long next = cart.version() == null ? 0 : cart.version() + 1;
    store.put(
        cart.id(), Cart.restore(cart.id(), cart.lines(), cart.createdAt(), cart.updatedAt(), next));
  }

  void put(Cart cart) {
    store.put(cart.id(), copy(cart));
  }

  void simulateConcurrentWrites(int count) {
    conflictsToSimulate = count;
  }

  Cart get(CartId id) {
    return store.get(id);
  }

  private static Cart copy(Cart cart) {
    return Cart.restore(
        cart.id(), cart.lines(), cart.createdAt(), cart.updatedAt(), cart.version());
  }
}
