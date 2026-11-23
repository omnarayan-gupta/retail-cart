package com.target.retailcart.app.application.view;

import java.time.Duration;
import java.util.Objects;

/**
 * @param currency currency of all prices; the Pricing API has none, so it's configured (D30, Q4)
 * @param lookupTimeout upper bound for any single dependency lookup while building a view; a slow
 *     lookup is treated as failed so one slow service can't stall the cart (D11, D14)
 */
public record CartViewSettings(String currency, Duration lookupTimeout) {

  public CartViewSettings {
    Objects.requireNonNull(currency, "currency must not be null");
    Objects.requireNonNull(lookupTimeout, "lookupTimeout must not be null");
  }
}
