package com.target.retailcart.app.application.view;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.target.retailcart.app.application.ActiveCarts;
import com.target.retailcart.app.application.AvailabilityPolicy;
import com.target.retailcart.app.application.CartNotFoundException;
import com.target.retailcart.app.application.PriceResolver;
import com.target.retailcart.app.domain.Cart;
import com.target.retailcart.app.domain.CartExpiryPolicy;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.CartLine;
import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.port.AvailabilityClient;
import com.target.retailcart.app.domain.port.CartRepository;
import com.target.retailcart.app.domain.port.DependencyUnavailableException;
import com.target.retailcart.app.domain.port.ItemClient;
import com.target.retailcart.app.domain.port.PricingClient;
import com.target.retailcart.app.domain.product.ItemAvailability;
import com.target.retailcart.app.domain.product.ItemDetails;
import com.target.retailcart.app.domain.product.ItemPrice;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CartViewServiceTest {

  private static final Instant NOW = Instant.parse("2026-11-22T10:00:00Z");
  private static final ItemId TEE = ItemId.of("54276335");
  private static final ItemId CLEANER = ItemId.of("15744221");

  private final CartRepository carts = mock(CartRepository.class);
  private final ItemClient items = mock(ItemClient.class);
  private final PricingClient pricing = mock(PricingClient.class);
  private final AvailabilityClient availability = mock(AvailabilityClient.class);
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private final CartViewService service =
      new CartViewService(
          new ActiveCarts(carts, CartExpiryPolicy.defaults(), clock),
          items,
          pricing,
          availability,
          new AvailabilityPolicy(),
          new PriceResolver(),
          executor,
          clock,
          new CartViewSettings("USD", Duration.ofMillis(300)));

  private CartId cartId;

  @BeforeEach
  void cartWithTwoTeesAndOneCleaner() {
    Cart cart =
        Cart.restore(
            CartId.newId(),
            List.of(new CartLine(TEE, 2, NOW), new CartLine(CLEANER, 1, NOW)),
            NOW,
            NOW,
            0L);
    cartId = cart.id();
    when(carts.findById(cartId)).thenReturn(Optional.of(cart));

    givenDetails(TEE, "Hanes Tee");
    givenDetails(CLEANER, "Lemon Cleaner");
    givenPrice(TEE, "17.61", "12.39", "SALE");
    givenPrice(CLEANER, "5.00", null, "REGULAR");
    givenAvailability(TEE, 312, 20);
    givenAvailability(CLEANER, 50, 10);
  }

  @AfterEach
  void shutdown() {
    executor.close();
  }

  @Test
  void pricesEveryLineWithTheCurrentPrice() {
    CartView view = service.view(cartId);

    assertThat(view.items())
        .extracting(CartViewLine::itemId, CartViewLine::unitPrice, CartViewLine::lineTotal)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(TEE, bd("12.39"), bd("24.78")),
            org.assertj.core.groups.Tuple.tuple(CLEANER, bd("5.00"), bd("5.00")));
    assertThat(view.items()).allMatch(line -> line.status() == LineStatus.OK);
    assertThat(view.subtotal()).isEqualByComparingTo("29.78");
    assertThat(view.itemCount()).isEqualTo(3);
    assertThat(view.currency()).isEqualTo("USD");
    assertThat(view.totalIncomplete()).isFalse();
    assertThat(view.warnings()).isEmpty();
    assertThat(view.pricedAt()).isEqualTo(NOW);
  }

  @Test
  void priceChangeIsReflectedOnTheNextView() {
    assertThat(service.view(cartId).subtotal()).isEqualByComparingTo("29.78");

    givenPrice(TEE, "17.61", "15.00", "SALE");

    assertThat(service.view(cartId).items().getFirst().unitPrice()).isEqualByComparingTo("15.00");
  }

  @Test
  void itemThatBecameUnavailableStaysButIsExcludedFromSubtotal() {
    givenAvailability(TEE, 0, 20);

    CartView view = service.view(cartId);
    CartViewLine tee = view.items().getFirst();

    assertThat(tee.status()).isEqualTo(LineStatus.UNAVAILABLE);
    assertThat(tee.availability()).isEqualTo(AvailabilityStatus.UNAVAILABLE);
    assertThat(view.subtotal()).isEqualByComparingTo("5.00");
    assertThat(view.totalIncomplete()).as("unavailable is a known state").isFalse();
    assertThat(view.warnings()).containsExactly("Item 54276335 is no longer available");
  }

  @Test
  void missingAvailabilityDataIsTreatedAsUnavailable() {
    when(availability.findAvailability(TEE)).thenReturn(Optional.empty());

    assertThat(service.view(cartId).items().getFirst().status()).isEqualTo(LineStatus.UNAVAILABLE);
  }

  @Test
  void pricingOutageNeverFallsBackToAnOldPrice() {
    when(pricing.findPrice(TEE)).thenThrow(outage("pricing"));

    CartView view = service.view(cartId);
    CartViewLine tee = view.items().getFirst();

    assertThat(tee.status()).isEqualTo(LineStatus.PRICE_UNAVAILABLE);
    assertThat(tee.unitPrice()).isNull();
    assertThat(tee.lineTotal()).isNull();
    assertThat(view.subtotal()).isEqualByComparingTo("5.00");
    assertThat(view.totalIncomplete()).isTrue();
  }

  @Test
  void availabilityOutageMarksLineUnknownAndTotalIncomplete() {
    when(availability.findAvailability(TEE)).thenThrow(outage("availability"));

    CartView view = service.view(cartId);

    assertThat(view.items().getFirst().availability()).isEqualTo(AvailabilityStatus.UNKNOWN);
    assertThat(view.items().getFirst().status()).isEqualTo(LineStatus.AVAILABILITY_UNKNOWN);
    assertThat(view.totalIncomplete()).isTrue();
  }

  @Test
  void itemServiceOutageIsCosmeticOnly() {
    when(items.findItem(TEE)).thenThrow(outage("item"));

    CartView view = service.view(cartId);
    CartViewLine tee = view.items().getFirst();

    assertThat(tee.status()).isEqualTo(LineStatus.DETAILS_UNAVAILABLE);
    assertThat(tee.name()).isNull();
    assertThat(tee.lineTotal()).isEqualByComparingTo("24.78");
    assertThat(view.subtotal()).isEqualByComparingTo("29.78");
    assertThat(view.totalIncomplete()).isFalse();
  }

  @Test
  void limitBelowCartQuantityIsFlaggedNotReduced() {
    givenAvailability(TEE, 312, 1); // cart holds 2

    CartViewLine tee = service.view(cartId).items().getFirst();

    assertThat(tee.status()).isEqualTo(LineStatus.QUANTITY_EXCEEDS_LIMIT);
    assertThat(tee.quantity()).isEqualTo(2);
    assertThat(tee.maxAllowed()).isEqualTo(1);
  }

  @Test
  void slowDependencyIsCutOffByTheLookupTimeout() {
    when(pricing.findPrice(TEE))
        .thenAnswer(invocation -> {
          Thread.sleep(2_000);
          return Optional.of(new ItemPrice(TEE, bd("1"), null, "REGULAR"));
        });

    long start = System.nanoTime();
    CartView view = service.view(cartId);
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertThat(view.items().getFirst().status()).isEqualTo(LineStatus.PRICE_UNAVAILABLE);
    assertThat(elapsedMillis).isLessThan(1_500);
  }

  @Test
  void callsRunInParallel() {
    // 2 lines x 3 lookups, each taking 200ms: sequential would be ~1.2s.
    when(items.findItem(any())).thenAnswer(slow(Optional.empty()));
    when(availability.findAvailability(any()))
        .thenAnswer(slow(Optional.of(new ItemAvailability(TEE, 10, 0))));
    when(pricing.findPrice(any()))
        .thenAnswer(slow(Optional.of(new ItemPrice(TEE, bd("1.00"), null, "REGULAR"))));

    long start = System.nanoTime();
    service.view(cartId);
    long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

    assertThat(elapsedMillis).isLessThan(600);
  }

  @Test
  void unknownCartIsNotFound() {
    assertThatThrownBy(() -> service.view(CartId.newId()))
        .isInstanceOf(CartNotFoundException.class);
  }

  private static org.mockito.stubbing.Answer<Object> slow(Object result) {
    return invocation -> {
      Thread.sleep(200);
      return result;
    };
  }

  private void givenDetails(ItemId id, String name) {
    when(items.findItem(id))
        .thenReturn(Optional.of(new ItemDetails(id, name, "Brand", null, 0, "MULTI-CHANNEL")));
  }

  private void givenPrice(ItemId id, String regular, String sale, String type) {
    when(pricing.findPrice(id))
        .thenReturn(
            Optional.of(new ItemPrice(id, bd(regular), sale == null ? null : bd(sale), type)));
  }

  private void givenAvailability(ItemId id, int units, int threshold) {
    when(availability.findAvailability(id))
        .thenReturn(Optional.of(new ItemAvailability(id, units, threshold)));
  }

  private static DependencyUnavailableException outage(String dependency) {
    return new DependencyUnavailableException(dependency, "timeout", null);
  }

  private static BigDecimal bd(String value) {
    return new BigDecimal(value);
  }
}
