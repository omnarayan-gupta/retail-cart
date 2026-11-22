package com.target.retailcart.app.infrastructure.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Cart settings from {@code application.yml} (prefix {@code cart}).
 *
 * @param ttl lifetime of a cart with items after its last write (D21)
 * @param emptyTtl lifetime of an empty cart after its last write (D21)
 * @param currency currency of all prices; the Pricing API has none (D30)
 * @param view cart view settings
 */
@ConfigurationProperties("cart")
public record CartProperties(
    @DefaultValue("P30D") Duration ttl,
    @DefaultValue("PT24H") Duration emptyTtl,
    @DefaultValue("USD") String currency,
    @DefaultValue View view) {

  /**
   * @param lookupTimeout safety net per dependency lookup when building a view (D11, D14)
   */
  public record View(@DefaultValue("PT1S") Duration lookupTimeout) {}
}
