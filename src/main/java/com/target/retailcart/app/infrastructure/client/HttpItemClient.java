package com.target.retailcart.app.infrastructure.client;

import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.port.ItemClient;
import com.target.retailcart.app.domain.product.ItemDetails;
import com.target.retailcart.app.infrastructure.client.ProductApi.ImageData;
import com.target.retailcart.app.infrastructure.client.ProductApi.ItemResponse;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.HtmlUtils;

/** HTTP adapter for the {@link ItemClient} port: {@code GET /product/v1/items/{id}}. */
@Component
class HttpItemClient implements ItemClient {

  private final RestClient restClient;
  private final ResilientCall call;

  HttpItemClient(
      RestClient productRestClient, CircuitBreakerRegistry breakers, RetryRegistry retries) {
    this.restClient = productRestClient;
    this.call = new ResilientCall("item", breakers, retries);
  }

  @Override
  public Optional<ItemDetails> findItem(ItemId itemId) {
    return call.find(() ->
            restClient
                .get()
                .uri(ProductApi.ITEM_PATH, itemId.value())
                .retrieve()
                .body(ItemResponse.class))
        .map(response -> toDomain(itemId, response));
  }

  private static ItemDetails toDomain(ItemId itemId, ItemResponse response) {
    return new ItemDetails(
        itemId,
        clean(response.smallDescription()),
        clean(response.brandName()),
        imageUrl(response.imageData()),
        response.ageRestriction() == null ? 0 : response.ageRestriction(),
        response.channelRestriction());
  }

  /** The catalog has HTML entities in names (e.g. {@code H26&#174;}) and trailing spaces. */
  static String clean(String text) {
    return text == null ? null : HtmlUtils.htmlUnescape(text).strip();
  }

  private static String imageUrl(ImageData image) {
    if (image == null || image.baseUrl() == null || image.primary() == null) {
      return null;
    }
    return image.baseUrl() + image.primary();
  }
}
