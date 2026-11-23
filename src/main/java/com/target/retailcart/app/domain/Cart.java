package com.target.retailcart.app.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * The cart aggregate. Enforces the cart's own rules (D24); everything that needs the outside world
 * (is the item available? what is the limit?) is decided by the application service and passed in.
 *
 * <ul>
 *   <li>One line per item: adding an item already in the cart merges the quantity (D9).
 *   <li>Quantity is always at least 1; removing is done with {@link #removeItem} (D18).
 *   <li>Increases are capped by the max the Availability service allows; decreases never are.
 *   <li>Every write moves {@code updatedAt}, which drives expiry (D21) and optimistic locking
 *       (D26).
 * </ul>
 *
 * <p>Plain Java on purpose: no Spring or JPA annotations, so rules are unit-tested in isolation and
 * the persistence technology can change without touching this class (D25).
 */
public final class Cart {

  private final CartId id;
  private final Map<ItemId, CartLine> lines; // insertion order = order items were added
  private final Instant createdAt;
  private Instant updatedAt;
  private final Long version; // optimistic-lock token owned by persistence; null for a new cart

  private Cart(
      CartId id, Collection<CartLine> lines, Instant createdAt, Instant updatedAt, Long version) {
    this.id = Objects.requireNonNull(id, "id must not be null");
    this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
    this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    this.version = version;
    this.lines = new LinkedHashMap<>();
    for (CartLine line : lines) {
      if (this.lines.putIfAbsent(line.itemId(), line) != null) {
        throw new IllegalArgumentException("duplicate line for item " + line.itemId());
      }
    }
  }

  /** A new, empty cart. Carts are created by the first add (D3), so it gets an item right away. */
  public static Cart create(CartId id, Instant now) {
    return new Cart(id, List.of(), now, now, null);
  }

  /** Rebuilds a cart from storage. Used only by the persistence adapter. */
  public static Cart restore(
      CartId id, Collection<CartLine> lines, Instant createdAt, Instant updatedAt, Long version) {
    return new Cart(id, lines, createdAt, updatedAt, version);
  }

  /**
   * Adds an item, or increases its quantity if it is already in the cart.
   *
   * @param maxAllowed the most of this item the cart may hold right now, from the Availability
   *     service (D29)
   * @return the resulting line
   * @throws QuantityLimitExceededException if the new total would exceed {@code maxAllowed}
   */
  public CartLine addItem(ItemId itemId, int quantity, int maxAllowed, Instant now) {
    Objects.requireNonNull(itemId, "itemId must not be null");
    requireValidQuantity(quantity);

    CartLine existing = lines.get(itemId);
    int newTotal = existing == null ? quantity : Math.addExact(existing.quantity(), quantity);
    if (newTotal > maxAllowed) {
      throw new QuantityLimitExceededException(itemId, newTotal, maxAllowed);
    }

    CartLine line =
        existing == null ? new CartLine(itemId, newTotal, now) : existing.withQuantity(newTotal);
    lines.put(itemId, line);
    touch(now);
    return line;
  }

  /**
   * True if changing the item to {@code newQuantity} is an increase, so the caller must ask the
   * Availability service for the current limit first. Decreases need no check (D18).
   *
   * @throws ItemNotInCartException if the item is not in the cart
   */
  public boolean isIncrease(ItemId itemId, int newQuantity) {
    return newQuantity > requireLine(itemId).quantity();
  }

  /**
   * Sets the item's quantity to an absolute value (D18), which makes the update safe to retry.
   *
   * @param maxAllowed required when the quantity goes up; ignored when it goes down
   * @return the resulting line
   * @throws ItemNotInCartException if the item is not in the cart
   * @throws QuantityLimitExceededException if an increase goes above {@code maxAllowed}
   */
  public CartLine updateQuantity(
      ItemId itemId, int newQuantity, OptionalInt maxAllowed, Instant now) {
    requireValidQuantity(newQuantity);
    CartLine existing = requireLine(itemId);

    if (newQuantity > existing.quantity()) {
      int max =
          maxAllowed.orElseThrow(
              () -> new IllegalStateException("maxAllowed is required to increase a quantity"));
      if (newQuantity > max) {
        throw new QuantityLimitExceededException(itemId, newQuantity, max);
      }
    }

    CartLine line = existing.withQuantity(newQuantity);
    lines.put(itemId, line);
    touch(now);
    return line;
  }

  /**
   * Removes an item. The cart stays, even when empty (D20).
   *
   * @throws ItemNotInCartException if the item is not in the cart
   */
  public void removeItem(ItemId itemId, Instant now) {
    requireLine(itemId);
    lines.remove(itemId);
    touch(now);
  }

  /**
   * Whether the cart has expired (D21). Expiry counts from the last write, so viewing a cart does
   * not keep it alive. Empty carts expire sooner.
   */
  public boolean isExpired(Instant now, CartExpiryPolicy policy) {
    var ttl = isEmpty() ? policy.emptyTtl() : policy.ttl();
    return !now.isBefore(updatedAt.plus(ttl));
  }

  public Optional<CartLine> line(ItemId itemId) {
    return Optional.ofNullable(lines.get(itemId));
  }

  /** Lines in the order the items were first added. */
  public List<CartLine> lines() {
    return List.copyOf(lines.values());
  }

  /** Total number of units across all lines (the number on a cart badge). */
  public int itemCount() {
    return lines.values().stream().mapToInt(CartLine::quantity).sum();
  }

  public boolean isEmpty() {
    return lines.isEmpty();
  }

  public CartId id() {
    return id;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Long version() {
    return version;
  }

  static void requireValidQuantity(int quantity) {
    if (quantity < 1) {
      throw new IllegalArgumentException("quantity must be at least 1, was " + quantity);
    }
  }

  private CartLine requireLine(ItemId itemId) {
    CartLine line = lines.get(Objects.requireNonNull(itemId, "itemId must not be null"));
    if (line == null) {
      throw new ItemNotInCartException(itemId);
    }
    return line;
  }

  private void touch(Instant now) {
    this.updatedAt = Objects.requireNonNull(now, "now must not be null");
  }
}
