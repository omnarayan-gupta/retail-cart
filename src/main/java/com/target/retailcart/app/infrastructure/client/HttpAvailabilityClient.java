package com.target.retailcart.app.infrastructure.client;

import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.port.AvailabilityClient;
import com.target.retailcart.app.domain.product.ItemAvailability;
import com.target.retailcart.app.infrastructure.client.ProductApi.AvailabilityResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP adapter for the {@link AvailabilityClient} port: {@code GET /product/v1/availability/{id}}.
 */
@Component
class HttpAvailabilityClient implements AvailabilityClient {

  private final RestClient restClient;
  private final ResilientCall call;

  HttpAvailabilityClient(
      RestClient productRestClient, CircuitBreakerRegistry breakers, RetryRegistry retries) {
    this.restClient = productRestClient;
    this.call = new ResilientCall("availability", breakers, retries);
  }

  @Override
  public Optional<ItemAvailability> findAvailability(ItemId itemId) {
    return call.find(() ->
            restClient
                .get()
                .uri(ProductApi.AVAILABILITY_PATH, itemId.value())
                .retrieve()
                .body(AvailabilityResponse.class))
        .map(r -> new ItemAvailability(itemId, r.availableUnits(), r.limitedQuantityThreshold()));
  }
}
