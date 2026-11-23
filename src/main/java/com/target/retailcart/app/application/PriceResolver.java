package com.target.retailcart.app.application;

import com.target.retailcart.app.domain.product.ItemPrice;
import java.math.BigDecimal;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Picks the price the customer pays (D30). Follows the Pricing service's own signal: when it says
 * {@code price_type = SALE} and a sale price exists, the sale price applies; otherwise the regular
 * price. Unknown price types fall back to regular (clarifying question 2).
 */
@Component
public class PriceResolver {

  static final String SALE = "SALE";

  /** The unit price to charge, or empty when the response has no usable price. */
  public Optional<BigDecimal> unitPrice(ItemPrice price) {
    if (SALE.equalsIgnoreCase(price.priceType()) && isUsable(price.sale())) {
      return Optional.of(price.sale());
    }
    return isUsable(price.regular()) ? Optional.of(price.regular()) : Optional.empty();
  }

  private static boolean isUsable(BigDecimal amount) {
    return amount != null && amount.signum() >= 0;
  }
}
