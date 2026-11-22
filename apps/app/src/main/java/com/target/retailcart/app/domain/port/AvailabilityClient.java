package com.target.retailcart.app.domain.port;

import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.product.ItemAvailability;
import java.util.Optional;

/** Port to the Availability service. */
public interface AvailabilityClient {

  /**
   * @return current availability, or empty if the service has no availability data (404)
   * @throws DependencyUnavailableException if the service fails or times out
   */
  Optional<ItemAvailability> findAvailability(ItemId itemId);
}
