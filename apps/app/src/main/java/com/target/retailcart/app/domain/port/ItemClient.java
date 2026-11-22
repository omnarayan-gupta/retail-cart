package com.target.retailcart.app.domain.port;

import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.product.ItemDetails;
import java.util.Optional;

/** Port to the Item service. */
public interface ItemClient {

  /**
   * @return the item's details, or empty if the Item service says it does not exist (404)
   * @throws DependencyUnavailableException if the service fails or times out
   */
  Optional<ItemDetails> findItem(ItemId itemId);
}
