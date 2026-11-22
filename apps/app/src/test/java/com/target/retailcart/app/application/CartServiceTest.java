package com.target.retailcart.app.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.target.retailcart.app.domain.Cart;
import com.target.retailcart.app.domain.CartExpiryPolicy;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.CartLine;
import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.ItemNotInCartException;
import com.target.retailcart.app.domain.QuantityLimitExceededException;
import com.target.retailcart.app.domain.port.AvailabilityClient;
import com.target.retailcart.app.domain.port.DependencyUnavailableException;
import com.target.retailcart.app.domain.port.ItemClient;
import com.target.retailcart.app.domain.product.ItemAvailability;
import com.target.retailcart.app.domain.product.ItemDetails;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CartServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
  private static final ItemId TEE = ItemId.of("54276335");

  private final InMemoryCartRepository carts = new InMemoryCartRepository();
  private final ItemClient items = mock(ItemClient.class);
  private final AvailabilityClient availability = mock(AvailabilityClient.class);

  private final CartService service =
      new CartService(
          carts,
          new ActiveCarts(carts, CartExpiryPolicy.defaults(), Clock.fixed(NOW, ZoneOffset.UTC)),
          items,
          availability,
          new AvailabilityPolicy(),
          Runnable::run, // run "parallel" calls inline: deterministic tests
          Clock.fixed(NOW, ZoneOffset.UTC));

  @BeforeEach
  void itemExistsWithStock() {
    givenItemExists(TEE);
    givenAvailability(TEE, 312, 20); // max 20
  }

  @Nested
  class AddToNewCart {

    @Test
    void createsCartWithTheItem() {
      CartItemResult result = service.addItemToNewCart(TEE, 2);

      assertThat(result.cartCreated()).isTrue();
      assertThat(result.quantity()).isEqualTo(2);
      assertThat(result.itemCount()).isEqualTo(2);
      assertThat(carts.get(result.cartId()).lines())
          .extracting(CartLine::itemId)
          .containsExactly(TEE);
    }

    @Test
    void unknownItemIsRejectedAsNotFound() {
      when(items.findItem(TEE)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.addItemToNewCart(TEE, 1))
          .isInstanceOf(ItemNotFoundException.class);
      assertThat(carts.saveAttempts).isZero();
    }

    @Test
    void unknownItemWinsOverAvailabilityOutage() {
      when(items.findItem(TEE)).thenReturn(Optional.empty());
      when(availability.findAvailability(TEE)).thenThrow(outage("availability"));

      assertThatThrownBy(() -> service.addItemToNewCart(TEE, 1))
          .isInstanceOf(ItemNotFoundException.class);
    }

    @Test
    void soldOutItemIsRejected() {
      givenAvailability(TEE, 0, 20);

      assertThatThrownBy(() -> service.addItemToNewCart(TEE, 1))
          .isInstanceOf(ItemUnavailableException.class);
    }

    @Test
    void itemWithoutAvailabilityDataIsRejected() {
      when(availability.findAvailability(TEE)).thenReturn(Optional.empty());

      assertThatThrownBy(() -> service.addItemToNewCart(TEE, 1))
          .isInstanceOf(ItemUnavailableException.class);
    }

    @Test
    void availabilityOutageFailsClosed() {
      when(availability.findAvailability(TEE)).thenThrow(outage("availability"));

      assertThatThrownBy(() -> service.addItemToNewCart(TEE, 1))
          .isInstanceOf(DependencyUnavailableException.class);
      assertThat(carts.saveAttempts).isZero();
    }

    @Test
    void itemServiceOutageDoesNotBlockAnAvailableItem() {
      when(items.findItem(TEE)).thenThrow(outage("item"));

      assertThat(service.addItemToNewCart(TEE, 1).quantity()).isEqualTo(1);
    }

    @Test
    void quantityAboveLimitIsRejected() {
      assertThatThrownBy(() -> service.addItemToNewCart(TEE, 21))
          .isInstanceOf(QuantityLimitExceededException.class);
    }
  }

  @Nested
  class AddToExistingCart {

    @Test
    void mergesWithExistingLine() {
      CartId id = existingCartWith(TEE, 3);

      CartItemResult result = service.addItem(id, TEE, 2);

      assertThat(result.cartCreated()).isFalse();
      assertThat(result.quantity()).isEqualTo(5);
      assertThat(carts.get(id).lines()).hasSize(1);
    }

    @Test
    void unknownCartFailsFastWithoutCallingDependencies() {
      assertThatThrownBy(() -> service.addItem(CartId.newId(), TEE, 1))
          .isInstanceOf(CartNotFoundException.class);
      verifyNoInteractions(items, availability);
    }

    @Test
    void expiredCartIsTreatedAsMissing() {
      Cart old = Cart.create(CartId.newId(), NOW.minus(Duration.ofDays(31)));
      old.addItem(TEE, 1, 20, NOW.minus(Duration.ofDays(31)));
      carts.put(old);

      assertThatThrownBy(() -> service.addItem(old.id(), TEE, 1))
          .isInstanceOf(CartNotFoundException.class);
    }
  }

  @Nested
  class UpdateQuantity {

    @Test
    void decreaseDoesNotCallAvailability() {
      CartId id = existingCartWith(TEE, 5);

      assertThat(service.updateQuantity(id, TEE, 2).quantity()).isEqualTo(2);
      verify(availability, never()).findAvailability(any());
    }

    @Test
    void increaseChecksCurrentLimit() {
      CartId id = existingCartWith(TEE, 2);
      givenAvailability(TEE, 312, 4);

      assertThat(service.updateQuantity(id, TEE, 4).quantity()).isEqualTo(4);
      assertThatThrownBy(() -> service.updateQuantity(id, TEE, 5))
          .isInstanceOf(QuantityLimitExceededException.class);
    }

    @Test
    void increaseOfSoldOutItemIsRejected() {
      CartId id = existingCartWith(TEE, 2);
      givenAvailability(TEE, 0, 20);

      assertThatThrownBy(() -> service.updateQuantity(id, TEE, 3))
          .isInstanceOf(ItemUnavailableException.class);
    }

    @Test
    void itemNotInCartIsRejected() {
      CartId id = existingCartWith(TEE, 2);

      assertThatThrownBy(() -> service.updateQuantity(id, ItemId.of("999"), 1))
          .isInstanceOf(ItemNotInCartException.class);
    }
  }

  @Nested
  class RemoveItem {

    @Test
    void removesLineAndKeepsCart() {
      CartId id = existingCartWith(TEE, 2);

      service.removeItem(id, TEE);

      assertThat(carts.get(id).isEmpty()).isTrue();
      verifyNoInteractions(items, availability);
    }

    @Test
    void removingMissingItemFails() {
      CartId id = existingCartWith(TEE, 2);

      assertThatThrownBy(() -> service.removeItem(id, ItemId.of("999")))
          .isInstanceOf(ItemNotInCartException.class);
    }
  }

  @Nested
  class ConcurrentWrites {

    @Test
    void retriesFromFreshLoadAfterAConflict() {
      CartId id = existingCartWith(TEE, 2);
      carts.simulateConcurrentWrites(1);

      assertThat(service.updateQuantity(id, TEE, 3).quantity()).isEqualTo(3);
      assertThat(carts.saveAttempts).isEqualTo(2);
    }

    @Test
    void givesUpAfterMaxAttempts() {
      CartId id = existingCartWith(TEE, 2);
      carts.simulateConcurrentWrites(CartService.MAX_ATTEMPTS);

      assertThatThrownBy(() -> service.removeItem(id, TEE))
          .isInstanceOf(ConcurrentCartModificationException.class);
      assertThat(carts.saveAttempts).isEqualTo(CartService.MAX_ATTEMPTS);
    }
  }

  private CartId existingCartWith(ItemId itemId, int quantity) {
    Cart cart =
        Cart.restore(CartId.newId(), List.of(new CartLine(itemId, quantity, NOW)), NOW, NOW, 0L);
    carts.put(cart);
    return cart.id();
  }

  private void givenItemExists(ItemId itemId) {
    when(items.findItem(itemId))
        .thenReturn(Optional.of(new ItemDetails(itemId, "Tee", "Hanes", null, 0, "MULTI-CHANNEL")));
  }

  private void givenAvailability(ItemId itemId, int units, int threshold) {
    when(availability.findAvailability(itemId))
        .thenReturn(Optional.of(new ItemAvailability(itemId, units, threshold)));
  }

  private static DependencyUnavailableException outage(String dependency) {
    return new DependencyUnavailableException(dependency, "timeout", null);
  }
}
