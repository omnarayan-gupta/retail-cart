package com.target.retailcart.app.infrastructure.client;

import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.port.PricingClient;
import com.target.retailcart.app.domain.product.ItemPrice;
import com.target.retailcart.app.infrastructure.client.ProductApi.PriceResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * HTTP adapter for the {@link PricingClient} port: {@code GET /product/v1/prices/{id}}. No caching
 * on purpose: the cart must always show the latest price (D7).
 */
@Component
class HttpPricingClient implements PricingClient {

  private final RestClient restClient;
  private final ResilientCall call;

  HttpPricingClient(
      RestClient productRestClient, CircuitBreakerRegistry breakers, RetryRegistry retries) {
    this.restClient = productRestClient;
    this.call = new ResilientCall("pricing", breakers, retries);
  }

  @Override
  public Optional<ItemPrice> findPrice(ItemId itemId) {
    return call.find(() ->
            restClient
                .get()
                .uri(ProductApi.PRICE_PATH, itemId.value())
                .retrieve()
                .body(PriceResponse.class))
        .map(r -> new ItemPrice(itemId, r.regular(), r.sale(), r.priceType()));
  }
}
