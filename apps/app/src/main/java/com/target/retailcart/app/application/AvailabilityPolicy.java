package com.target.retailcart.app.application;

import com.target.retailcart.app.domain.product.ItemAvailability;
import org.springframework.stereotype.Component;

/**
 * Turns an Availability service response into the cart's rules (D29).
 *
 * <p>Assumption (clarifying question 1): {@code limited_quantity_threshold} may be a per-cart
 * purchase limit or just a low-stock indicator. Capping at {@code min(available_units, threshold)}
 * is safe under either reading: we never allow more than is in stock, and we respect the threshold
 * if it is a limit.
 */
@Component
public class AvailabilityPolicy {

  /** An item can be ordered only if at least one unit is available. */
  public boolean isAvailable(ItemAvailability availability) {
    return availability.availableUnits() > 0;
  }

  /** The most of this item a cart may hold right now; 0 when unavailable. */
  public int maxAllowed(ItemAvailability availability) {
    int units = Math.max(availability.availableUnits(), 0);
    int threshold = availability.limitedQuantityThreshold();
    return threshold > 0 ? Math.min(units, threshold) : units;
  }
}
