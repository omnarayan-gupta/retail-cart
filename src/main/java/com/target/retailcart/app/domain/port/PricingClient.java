package com.target.retailcart.app.domain.port;

import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.product.ItemPrice;
import java.util.Optional;

/** Port to the Pricing service. */
public interface PricingClient {

  /**
   * @return the current price, or empty if the Pricing service has no price for the item (404)
   * @throws DependencyUnavailableException if the service fails or times out
   */
  Optional<ItemPrice> findPrice(ItemId itemId);
}
