package com.target.retailcart.app.infrastructure.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * Wire format of the Target product services ({@code /product/v1}, snake_case JSON). These types
 * never leave this package: adapters map them to domain records, so a contract change stays here.
 * Unknown fields are ignored (tolerant reader), so new fields on their side don't break us.
 */
final class ProductApi {

  static final String BASE_PATH = "/product/v1";
  static final String ITEM_PATH = BASE_PATH + "/items/{id}";
  static final String PRICE_PATH = BASE_PATH + "/prices/{id}";
  static final String AVAILABILITY_PATH = BASE_PATH + "/availability/{id}";

  private ProductApi() {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ItemResponse(
      @JsonProperty("item_id") String itemId,
      @JsonProperty("small_description") String smallDescription,
      @JsonProperty("brand_name") String brandName,
      @JsonProperty("channel_restriction") String channelRestriction,
      @JsonProperty("age_restriction") Integer ageRestriction,
      @JsonProperty("image_data") ImageData imageData) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record ImageData(
      @JsonProperty("primary") String primary, @JsonProperty("base_url") String baseUrl) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record PriceResponse(
      @JsonProperty("item_id") String itemId,
      @JsonProperty("regular") BigDecimal regular,
      @JsonProperty("sale") BigDecimal sale,
      @JsonProperty("price_type") String priceType) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record AvailabilityResponse(
      @JsonProperty("item_id") String itemId,
      @JsonProperty("available_units") int availableUnits,
      @JsonProperty("limited_quantity_threshold") int limitedQuantityThreshold) {}
}
