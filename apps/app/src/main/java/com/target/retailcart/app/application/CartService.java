package com.target.retailcart.app.application;

import com.target.retailcart.app.domain.Cart;
import com.target.retailcart.app.domain.CartExpiryPolicy;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.CartLine;
import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.port.AvailabilityClient;
import com.target.retailcart.app.domain.port.CartRepository;
import com.target.retailcart.app.domain.port.DependencyUnavailableException;
import com.target.retailcart.app.domain.port.ItemClient;
import com.target.retailcart.app.domain.product.ItemAvailability;
import com.target.retailcart.app.domain.product.ItemDetails;
import java.time.Clock;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Write side of the cart: add, update quantity, remove (D17: writes are cheap, no pricing calls).
 *
 * <p>Flow for every write: load the cart → ask the outside world what it needs (remote calls happen
 * here, outside any DB transaction, D26a) → let the {@link Cart} apply its rules → save. The save
 * is guarded by optimistic locking; on a conflict the whole flow is retried from a fresh load
 * (D26).
 */
@Service
public class CartService {

  static final int MAX_ATTEMPTS = 3;
  private static final Logger log = LoggerFactory.getLogger(CartService.class);

  private final CartRepository carts;
  private final ItemClient items;
  private final AvailabilityClient availability;
  private final AvailabilityPolicy availabilityPolicy;
  private final CartExpiryPolicy expiryPolicy;
  private final Executor productCallExecutor;
  private final Clock clock;

  public CartService(
      CartRepository carts,
      ItemClient items,
      AvailabilityClient availability,
      AvailabilityPolicy availabilityPolicy,
      CartExpiryPolicy expiryPolicy,
      @Qualifier("productCallExecutor") Executor productCallExecutor,
      Clock clock) {
    this.carts = carts;
    this.items = items;
    this.availability = availability;
    this.availabilityPolicy = availabilityPolicy;
    this.expiryPolicy = expiryPolicy;
    this.productCallExecutor = productCallExecutor;
    this.clock = clock;
  }

  /** First add: creates the cart and adds the item in one call (D3, D4). */
  public CartItemResult addItemToNewCart(ItemId itemId, int quantity) {
    int maxAllowed = checkCanBeAdded(itemId);
    Cart cart = Cart.create(CartId.newId(), clock.instant());
    CartLine line = cart.addItem(itemId, quantity, maxAllowed, clock.instant());
    carts.save(cart);
    return result(cart, line, true);
  }

  /** Adds an item to an existing cart, merging with an existing line (D9). */
  public CartItemResult addItem(CartId cartId, ItemId itemId, int quantity) {
    loadActiveCart(cartId); // fail fast on a bad cartId before calling dependencies
    int maxAllowed = checkCanBeAdded(itemId);
    return withRetry(
        cartId,
        cart -> result(cart, cart.addItem(itemId, quantity, maxAllowed, clock.instant()), false));
  }

  /** Sets an absolute quantity (D18). Only increases consult the Availability service. */
  public CartItemResult updateQuantity(CartId cartId, ItemId itemId, int quantity) {
    return withRetry(
        cartId,
        cart -> {
          OptionalInt maxAllowed =
              cart.isIncrease(itemId, quantity)
                  ? OptionalInt.of(currentMaxAllowed(itemId))
                  : OptionalInt.empty();
          CartLine line = cart.updateQuantity(itemId, quantity, maxAllowed, clock.instant());
          return result(cart, line, false);
        });
  }

  /** Removes an item; the cart stays even when empty (D20). No dependency calls. */
  public void removeItem(CartId cartId, ItemId itemId) {
    withRetry(
        cartId,
        cart -> {
          cart.removeItem(itemId, clock.instant());
          return null;
        });
  }

  /** Loads a cart, treating an expired one as missing (D21). */
  Cart loadActiveCart(CartId cartId) {
    return carts
        .findById(cartId)
        .filter(cart -> !cart.isExpired(clock.instant(), expiryPolicy))
        .orElseThrow(() -> new CartNotFoundException(cartId));
  }

  /**
   * Add-time check (D31): calls Item and Availability in parallel.
   *
   * <ul>
   *   <li>Item says 404 → {@link ItemNotFoundException} (422), whatever Availability says.
   *   <li>Availability fails → {@link DependencyUnavailableException} (503): we can't prove the
   *       item is available, so the write fails closed (D11).
   *   <li>No availability data, or no units → {@link ItemUnavailableException} (409).
   * </ul>
   *
   * <p>The Item lookup only decides 422 vs 409; if it fails we carry on with Availability's answer.
   *
   * @return the most of this item a cart may hold right now
   */
  private int checkCanBeAdded(ItemId itemId) {
    CompletableFuture<Optional<ItemDetails>> item =
        CompletableFuture.supplyAsync(() -> items.findItem(itemId), productCallExecutor);
    CompletableFuture<Optional<ItemAvailability>> stock =
        CompletableFuture.supplyAsync(
            () -> availability.findAvailability(itemId), productCallExecutor);

    try {
      if (await(item).isEmpty()) {
        throw new ItemNotFoundException(itemId);
      }
    } catch (DependencyUnavailableException e) {
      log.warn("Item lookup failed for {}; relying on availability only", itemId, e);
    }

    return await(stock)
        .filter(availabilityPolicy::isAvailable)
        .map(availabilityPolicy::maxAllowed)
        .orElseThrow(() -> new ItemUnavailableException(itemId));
  }

  /** Update-time check: Availability only (the item is already known to be in the cart). */
  private int currentMaxAllowed(ItemId itemId) {
    return availability
        .findAvailability(itemId)
        .filter(availabilityPolicy::isAvailable)
        .map(availabilityPolicy::maxAllowed)
        .orElseThrow(() -> new ItemUnavailableException(itemId));
  }

  /** Load → mutate → save, retried from a fresh load on an optimistic-lock conflict (D26). */
  private <T> T withRetry(CartId cartId, Function<Cart, T> change) {
    OptimisticLockingFailureException lastConflict = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      Cart cart = loadActiveCart(cartId);
      T outcome = change.apply(cart);
      try {
        carts.save(cart);
        return outcome;
      } catch (OptimisticLockingFailureException conflict) {
        log.info("Concurrent update of cart {} (attempt {}/{})", cartId, attempt, MAX_ATTEMPTS);
        lastConflict = conflict;
      }
    }
    throw new ConcurrentCartModificationException(cartId, lastConflict);
  }

  private static CartItemResult result(Cart cart, CartLine line, boolean created) {
    return new CartItemResult(cart.id(), line.itemId(), line.quantity(), cart.itemCount(), created);
  }

  private static <T> T await(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw e;
    }
  }
}
