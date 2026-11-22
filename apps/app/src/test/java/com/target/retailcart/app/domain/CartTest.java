package com.target.retailcart.app.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class CartTest {

  private static final Instant T0 = Instant.parse("2026-11-22T10:00:00Z");
  private static final Instant T1 = T0.plusSeconds(60);
  private static final ItemId TEE = ItemId.of("54276335");
  private static final ItemId CLEANER = ItemId.of("15744221");
  private static final int MAX = 20;

  private final Cart cart = Cart.create(CartId.newId(), T0);

  @Nested
  class AddItem {

    @Test
    void addsNewLine() {
      CartLine line = cart.addItem(TEE, 2, MAX, T1);

      assertThat(line.quantity()).isEqualTo(2);
      assertThat(cart.lines()).containsExactly(line);
      assertThat(cart.itemCount()).isEqualTo(2);
      assertThat(cart.updatedAt()).isEqualTo(T1);
    }

    @Test
    void addingSameItemAgainMergesQuantityIntoOneLine() {
      cart.addItem(TEE, 2, MAX, T0);
      CartLine line = cart.addItem(TEE, 3, MAX, T1);

      assertThat(line.quantity()).isEqualTo(5);
      assertThat(cart.lines()).hasSize(1);
      assertThat(line.addedAt()).as("keeps original add time").isEqualTo(T0);
    }

    @Test
    void keepsLinesInTheOrderItemsWereAdded() {
      cart.addItem(TEE, 1, MAX, T0);
      cart.addItem(CLEANER, 1, MAX, T0);
      cart.addItem(TEE, 1, MAX, T1);

      assertThat(cart.lines()).extracting(CartLine::itemId).containsExactly(TEE, CLEANER);
      assertThat(cart.itemCount()).isEqualTo(3);
    }

    @Test
    void allowsAddingExactlyUpToTheLimit() {
      assertThat(cart.addItem(TEE, MAX, MAX, T1).quantity()).isEqualTo(MAX);
    }

    @Test
    void rejectsQuantityAboveLimit() {
      assertThatThrownBy(() -> cart.addItem(TEE, MAX + 1, MAX, T1))
          .isInstanceOf(QuantityLimitExceededException.class)
          .satisfies(e -> {
            var ex = (QuantityLimitExceededException) e;
            assertThat(ex.requestedQuantity()).isEqualTo(MAX + 1);
            assertThat(ex.maxAllowed()).isEqualTo(MAX);
          });
      assertThat(cart.isEmpty()).isTrue();
      assertThat(cart.updatedAt()).as("failed add does not touch the cart").isEqualTo(T0);
    }

    @Test
    void rejectsMergeWhenCombinedTotalExceedsLimit() {
      cart.addItem(TEE, 15, MAX, T0);

      assertThatThrownBy(() -> cart.addItem(TEE, 6, MAX, T1))
          .isInstanceOf(QuantityLimitExceededException.class)
          .hasMessageContaining("21");
      assertThat(cart.line(TEE)).get().extracting(CartLine::quantity).isEqualTo(15);
    }

    @Test
    void rejectsZeroOrNegativeQuantity() {
      assertThatThrownBy(() -> cart.addItem(TEE, 0, MAX, T1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> cart.addItem(TEE, -1, MAX, T1))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  class UpdateQuantity {

    @Test
    void decreaseNeedsNoLimit() {
      cart.addItem(TEE, 5, MAX, T0);

      assertThat(cart.isIncrease(TEE, 2)).isFalse();
      CartLine line = cart.updateQuantity(TEE, 2, OptionalInt.empty(), T1);

      assertThat(line.quantity()).isEqualTo(2);
      assertThat(cart.updatedAt()).isEqualTo(T1);
    }

    @Test
    void increaseWithinLimitSucceeds() {
      cart.addItem(TEE, 2, MAX, T0);

      assertThat(cart.isIncrease(TEE, 4)).isTrue();
      assertThat(cart.updateQuantity(TEE, 4, OptionalInt.of(MAX), T1).quantity()).isEqualTo(4);
    }

    @Test
    void increaseAboveLimitIsRejected() {
      cart.addItem(TEE, 2, MAX, T0);

      assertThatThrownBy(() -> cart.updateQuantity(TEE, 25, OptionalInt.of(MAX), T1))
          .isInstanceOf(QuantityLimitExceededException.class);
      assertThat(cart.line(TEE)).get().extracting(CartLine::quantity).isEqualTo(2);
    }

    @Test
    void increaseWithoutLimitIsAProgrammingError() {
      cart.addItem(TEE, 2, MAX, T0);

      assertThatThrownBy(() -> cart.updateQuantity(TEE, 3, OptionalInt.empty(), T1))
          .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void decreaseIsAllowedEvenWhenLimitHasDroppedBelowCurrentQuantity() {
      // D22: the limit fell from 20 to 3 after the user added 10; reducing to 5 must still work.
      cart.addItem(TEE, 10, MAX, T0);

      assertThat(cart.updateQuantity(TEE, 5, OptionalInt.of(3), T1).quantity()).isEqualTo(5);
    }

    @Test
    void settingSameQuantityIsIdempotent() {
      cart.addItem(TEE, 3, MAX, T0);

      cart.updateQuantity(TEE, 3, OptionalInt.empty(), T1);
      cart.updateQuantity(TEE, 3, OptionalInt.empty(), T1);

      assertThat(cart.line(TEE)).get().extracting(CartLine::quantity).isEqualTo(3);
    }

    @Test
    void rejectsZeroQuantityBecauseRemovalUsesDelete() {
      cart.addItem(TEE, 3, MAX, T0);

      assertThatThrownBy(() -> cart.updateQuantity(TEE, 0, OptionalInt.empty(), T1))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void updatingItemNotInCartFails() {
      assertThatThrownBy(() -> cart.updateQuantity(TEE, 1, OptionalInt.of(MAX), T1))
          .isInstanceOf(ItemNotInCartException.class);
      assertThatThrownBy(() -> cart.isIncrease(TEE, 1)).isInstanceOf(ItemNotInCartException.class);
    }
  }

  @Nested
  class RemoveItem {

    @Test
    void removesLineAndKeepsEmptyCart() {
      cart.addItem(TEE, 2, MAX, T0);

      cart.removeItem(TEE, T1);

      assertThat(cart.isEmpty()).isTrue();
      assertThat(cart.itemCount()).isZero();
      assertThat(cart.updatedAt()).isEqualTo(T1);
    }

    @Test
    void removingItemNotInCartFails() {
      assertThatThrownBy(() -> cart.removeItem(TEE, T1))
          .isInstanceOf(ItemNotInCartException.class)
          .extracting(e -> ((ItemNotInCartException) e).itemId())
          .isEqualTo(TEE);
    }
  }

  @Nested
  class Expiry {

    private final CartExpiryPolicy policy = CartExpiryPolicy.defaults(); // 30 days / 24 hours

    @Test
    void cartWithItemsLivesThirtyDaysFromLastWrite() {
      cart.addItem(TEE, 1, MAX, T0);

      assertThat(cart.isExpired(T0.plus(Duration.ofDays(30)).minusSeconds(1), policy)).isFalse();
      assertThat(cart.isExpired(T0.plus(Duration.ofDays(30)), policy)).isTrue();
    }

    @Test
    void emptyCartLivesTwentyFourHours() {
      cart.addItem(TEE, 1, MAX, T0);
      cart.removeItem(TEE, T0);

      assertThat(cart.isExpired(T0.plus(Duration.ofHours(24)).minusSeconds(1), policy)).isFalse();
      assertThat(cart.isExpired(T0.plus(Duration.ofHours(24)), policy)).isTrue();
    }

    @Test
    void writesResetTheClock() {
      cart.addItem(TEE, 1, MAX, T0);
      Instant later = T0.plus(Duration.ofDays(29));
      cart.addItem(TEE, 1, MAX, later);

      assertThat(cart.isExpired(T0.plus(Duration.ofDays(31)), policy)).isFalse();
    }
  }

  @Nested
  class Restore {

    @Test
    void rebuildsCartFromStoredState() {
      CartId id = CartId.newId();
      var lines = List.of(new CartLine(TEE, 2, T0), new CartLine(CLEANER, 1, T0));

      Cart restored = Cart.restore(id, lines, T0, T1, 7L);

      assertThat(restored.id()).isEqualTo(id);
      assertThat(restored.lines()).containsExactlyElementsOf(lines);
      assertThat(restored.version()).isEqualTo(7L);
      assertThat(restored.updatedAt()).isEqualTo(T1);
    }

    @Test
    void rejectsDuplicateLinesFromStorage() {
      var lines = List.of(new CartLine(TEE, 2, T0), new CartLine(TEE, 1, T0));

      assertThatThrownBy(() -> Cart.restore(CartId.newId(), lines, T0, T0, 0L))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void linesViewIsReadOnly() {
    cart.addItem(TEE, 1, MAX, T0);

    assertThatThrownBy(() -> cart.lines().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void itemIdRejectsBlankAndOversizedValues() {
    assertThatThrownBy(() -> ItemId.of(" ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ItemId.of("1".repeat(33)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
