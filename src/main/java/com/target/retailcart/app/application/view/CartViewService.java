package com.target.retailcart.app.application.view;

import com.target.retailcart.app.application.ActiveCarts;
import com.target.retailcart.app.application.AvailabilityPolicy;
import com.target.retailcart.app.application.PriceResolver;
import com.target.retailcart.app.domain.Cart;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.CartLine;
import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.port.AvailabilityClient;
import com.target.retailcart.app.domain.port.ItemClient;
import com.target.retailcart.app.domain.port.PricingClient;
import com.target.retailcart.app.domain.product.ItemAvailability;
import com.target.retailcart.app.domain.product.ItemDetails;
import com.target.retailcart.app.domain.product.ItemPrice;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * Read side: builds the priced cart live on every request (D7).
 *
 * <p>For every line, Item, Pricing and Availability are called at the same time, and all lines run
 * at the same time (D14), so latency is roughly the slowest single call, not the sum. Each lookup
 * is bounded by a timeout and its failure only affects its own line: the view always returns, with
 * a per-line status explaining anything missing (D11). A price is never taken from anywhere but the
 * Pricing service.
 */
@Service
public class CartViewService {

  private static final Logger log = LoggerFactory.getLogger(CartViewService.class);

  private final ActiveCarts activeCarts;
  private final ItemClient items;
  private final PricingClient pricing;
  private final AvailabilityClient availability;
  private final AvailabilityPolicy availabilityPolicy;
  private final PriceResolver priceResolver;
  private final Executor productCallExecutor;
  private final Clock clock;
  private final CartViewSettings settings;

  public CartViewService(
      ActiveCarts activeCarts,
      ItemClient items,
      PricingClient pricing,
      AvailabilityClient availability,
      AvailabilityPolicy availabilityPolicy,
      PriceResolver priceResolver,
      @Qualifier("productCallExecutor") Executor productCallExecutor,
      Clock clock,
      CartViewSettings settings) {
    this.activeCarts = activeCarts;
    this.items = items;
    this.pricing = pricing;
    this.availability = availability;
    this.availabilityPolicy = availabilityPolicy;
    this.priceResolver = priceResolver;
    this.productCallExecutor = productCallExecutor;
    this.clock = clock;
    this.settings = settings;
  }

  /**
   * @throws com.target.retailcart.app.application.CartNotFoundException if missing or expired
   */
  public CartView view(CartId cartId) {
    Cart cart = activeCarts.load(cartId);

    // Start every lookup for every line before waiting on any of them.
    List<CompletableFuture<CartViewLine>> pending =
        cart.lines().stream().map(this::lineView).toList();
    List<CartViewLine> lines = pending.stream().map(CompletableFuture::join).toList();

    BigDecimal subtotal = BigDecimal.ZERO;
    boolean totalIncomplete = false;
    List<String> warnings = new ArrayList<>();
    for (CartViewLine line : lines) {
      if (countsTowardSubtotal(line)) {
        subtotal = subtotal.add(line.lineTotal());
      } else if (line.availability() != AvailabilityStatus.UNAVAILABLE) {
        totalIncomplete = true; // it might count, but we couldn't price or check it
      }
      warning(line).ifPresent(warnings::add);
    }

    return new CartView(
        cart.id(),
        lines,
        cart.itemCount(),
        subtotal.setScale(2, RoundingMode.HALF_EVEN),
        settings.currency(),
        totalIncomplete,
        List.copyOf(warnings),
        clock.instant());
  }

  private CompletableFuture<CartViewLine> lineView(CartLine line) {
    ItemId id = line.itemId();
    var details = lookup("item", id, () -> items.findItem(id));
    var price = lookup("pricing", id, () -> pricing.findPrice(id));
    var stock = lookup("availability", id, () -> availability.findAvailability(id));
    return CompletableFuture.allOf(details, price, stock)
        .thenApply(done -> assemble(line, details.join(), price.join(), stock.join()));
  }

  /** Runs one dependency call; failures and timeouts become {@link Lookup.Failed}, never errors. */
  private <T> CompletableFuture<Lookup<T>> lookup(
      String dependency, ItemId itemId, Supplier<Optional<T>> call) {
    return CompletableFuture.supplyAsync(() -> Lookup.of(call.get()), productCallExecutor)
        .exceptionally(e -> {
          log.warn("{} lookup failed for item {}: {}", dependency, itemId, e.toString());
          return new Lookup.Failed<>();
        })
        .completeOnTimeout(
            new Lookup.Failed<>(), settings.lookupTimeout().toMillis(), TimeUnit.MILLISECONDS);
  }

  private CartViewLine assemble(
      CartLine line,
      Lookup<ItemDetails> detailsLookup,
      Lookup<ItemPrice> priceLookup,
      Lookup<ItemAvailability> stockLookup) {

    Optional<ItemDetails> details = detailsLookup.found();
    Optional<ItemPrice> price = priceLookup.found();
    Optional<BigDecimal> unitPrice = price.flatMap(priceResolver::unitPrice);

    AvailabilityStatus availabilityStatus;
    Integer maxAllowed = null;
    switch (stockLookup) {
      case Lookup.Found<ItemAvailability>(ItemAvailability found) -> {
        availabilityStatus =
            availabilityPolicy.isAvailable(found)
                ? AvailabilityStatus.AVAILABLE
                : AvailabilityStatus.UNAVAILABLE;
        maxAllowed = availabilityPolicy.maxAllowed(found);
      }
      case Lookup.NotFound<ItemAvailability> notFound ->
          availabilityStatus = AvailabilityStatus.UNAVAILABLE; // no data: treat as unavailable (Q5)
      case Lookup.Failed<ItemAvailability> failed ->
          availabilityStatus = AvailabilityStatus.UNKNOWN;
    }

    LineStatus status;
    if (availabilityStatus == AvailabilityStatus.UNAVAILABLE) {
      status = LineStatus.UNAVAILABLE;
    } else if (availabilityStatus == AvailabilityStatus.UNKNOWN) {
      status = LineStatus.AVAILABILITY_UNKNOWN;
    } else if (unitPrice.isEmpty()) {
      status = LineStatus.PRICE_UNAVAILABLE;
    } else if (maxAllowed != null && line.quantity() > maxAllowed) {
      status = LineStatus.QUANTITY_EXCEEDS_LIMIT;
    } else if (details.isEmpty()) {
      status = LineStatus.DETAILS_UNAVAILABLE;
    } else {
      status = LineStatus.OK;
    }

    return new CartViewLine(
        line.itemId(),
        details.map(ItemDetails::name).orElse(null),
        details.map(ItemDetails::brand).orElse(null),
        details.map(ItemDetails::imageUrl).orElse(null),
        line.quantity(),
        unitPrice.orElse(null),
        price.map(ItemPrice::regular).orElse(null),
        price.map(ItemPrice::priceType).orElse(null),
        unitPrice.map(p -> p.multiply(BigDecimal.valueOf(line.quantity()))).orElse(null),
        availabilityStatus,
        maxAllowed,
        details.map(ItemDetails::ageRestriction).orElse(null),
        details.map(ItemDetails::channelRestriction).orElse(null),
        status);
  }

  /** Lines over the limit still count: the items are available, checkout enforces the limit. */
  private static boolean countsTowardSubtotal(CartViewLine line) {
    return line.availability() == AvailabilityStatus.AVAILABLE && line.lineTotal() != null;
  }

  private static Optional<String> warning(CartViewLine line) {
    String item = "Item " + line.itemId();
    return Optional.ofNullable(
        switch (line.status()) {
          case UNAVAILABLE -> item + " is no longer available";
          case AVAILABILITY_UNKNOWN -> item + ": availability could not be checked";
          case PRICE_UNAVAILABLE -> item + ": current price is unavailable";
          case QUANTITY_EXCEEDS_LIMIT ->
              item + ": quantity exceeds the current limit of " + line.maxAllowed();
          case DETAILS_UNAVAILABLE -> item + ": product details are unavailable";
          case OK -> null;
        });
  }
}
