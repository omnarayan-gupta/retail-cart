package com.target.retailcart.app.infrastructure.persistence;

import com.target.retailcart.app.domain.Cart;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.CartLine;
import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.port.CartRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL adapter for the {@link CartRepository} port (D8). Maps between the domain {@link Cart}
 * and JPA entities (D25), and enforces optimistic locking (D26):
 *
 * <ol>
 *   <li>The domain cart carries the version it was loaded with; a stale version fails fast.
 *   <li>Every save force-increments the cart's version, so two writers that both passed the check
 *       can't both commit: the second gets a conflict at commit time. This also covers changes that
 *       only touch {@code cart_item} rows.
 * </ol>
 *
 * <p>Each call is its own short transaction; no network call ever runs inside one (D26a).
 */
@Repository
class JpaCartRepository implements CartRepository {

  private final CartJpaRepository jpa;
  private final EntityManager entityManager;

  JpaCartRepository(CartJpaRepository jpa, EntityManager entityManager) {
    this.jpa = jpa;
    this.entityManager = entityManager;
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Cart> findById(CartId id) {
    return jpa.findWithItems(id.value()).map(JpaCartRepository::toDomain);
  }

  @Override
  @Transactional
  public void save(Cart cart) {
    if (cart.version() == null) {
      insert(cart);
    } else {
      update(cart);
    }
  }

  private void insert(Cart cart) {
    var entity = new CartEntity(cart.id().value(), cart.createdAt(), cart.updatedAt());
    for (CartLine line : cart.lines()) {
      entity.getItems().add(toEntity(entity, line));
    }
    entityManager.persist(entity);
  }

  private void update(Cart cart) {
    CartEntity entity =
        jpa.findWithItems(cart.id().value())
            .orElseThrow(() -> conflict(cart, "cart no longer exists"));
    if (entity.getVersion() != cart.version()) {
      throw conflict(cart, "stale version " + cart.version() + ", current " + entity.getVersion());
    }
    if (entity.getUpdatedAt().equals(cart.updatedAt())) {
      // Nothing on the cart row changes, so Hibernate wouldn't bump the version on its own.
      entityManager.lock(entity, LockModeType.OPTIMISTIC_FORCE_INCREMENT);
    } else {
      // A dirty cart row gets "version = version + 1 ... where version = ?" at flush.
      entity.setUpdatedAt(cart.updatedAt());
    }
    syncLines(entity, cart);
  }

  /** Applies the domain lines to the entity: update quantities, drop removed, add new. */
  private static void syncLines(CartEntity entity, Cart cart) {
    Map<String, CartLine> wanted = new HashMap<>();
    cart.lines().forEach(line -> wanted.put(line.itemId().value(), line));

    entity.getItems().removeIf(item -> !wanted.containsKey(item.getItemId()));
    for (CartItemEntity item : entity.getItems()) {
      item.setQuantity(wanted.get(item.getItemId()).quantity());
    }

    Set<String> existing =
        entity.getItems().stream().map(CartItemEntity::getItemId).collect(Collectors.toSet());
    cart.lines().stream()
        .filter(line -> !existing.contains(line.itemId().value()))
        .forEach(line -> entity.getItems().add(toEntity(entity, line)));
  }

  private static CartItemEntity toEntity(CartEntity cart, CartLine line) {
    return new CartItemEntity(cart, line.itemId().value(), line.quantity(), line.addedAt());
  }

  private static Cart toDomain(CartEntity entity) {
    return Cart.restore(
        new CartId(entity.getId()),
        entity.getItems().stream()
            .map(item ->
                new CartLine(ItemId.of(item.getItemId()), item.getQuantity(), item.getAddedAt()))
            .toList(),
        entity.getCreatedAt(),
        entity.getUpdatedAt(),
        entity.getVersion());
  }

  private static ObjectOptimisticLockingFailureException conflict(Cart cart, String reason) {
    return new ObjectOptimisticLockingFailureException(
        "Cart " + cart.id() + " was modified concurrently: " + reason, null);
  }
}
