package com.target.retailcart.app.infrastructure.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.notFound;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.port.AvailabilityClient;
import com.target.retailcart.app.domain.port.DependencyUnavailableException;
import com.target.retailcart.app.domain.port.ItemClient;
import com.target.retailcart.app.domain.port.PricingClient;
import com.target.retailcart.app.domain.product.ItemAvailability;
import com.target.retailcart.app.domain.product.ItemDetails;
import com.target.retailcart.app.domain.product.ItemPrice;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

/**
 * The three HTTP adapters against WireMock standing in for the Target product container, with the
 * real Spring context: {@code application.yml} timeouts, Resilience4j retry and circuit breakers.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@EnableWireMock(
    @ConfigureWireMock(name = "product", baseUrlProperties = "product-service.base-url"))
@Testcontainers
class ProductClientsTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final ItemId TEE = ItemId.of("54276335");
  private static final String ITEM_URL = "/product/v1/items/54276335";
  private static final String PRICE_URL = "/product/v1/prices/54276335";
  private static final String AVAILABILITY_URL = "/product/v1/availability/54276335";

  @InjectWireMock("product")
  WireMockServer wireMock;

  @Autowired ItemClient items;
  @Autowired PricingClient pricing;
  @Autowired AvailabilityClient availability;
  @Autowired CircuitBreakerRegistry breakers;

  @BeforeEach
  void reset() {
    wireMock.resetAll();
    breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
  }

  // --- mapping the contract -------------------------------------------------------------------

  @Test
  void mapsItemDetailsAndCleansUpCatalogText() {
    wireMock.stubFor(
        get(ITEM_URL)
            .willReturn(
                okJson(
                    """
                    {"item_id":"54276335","small_description":"Haggar H26&#174; Men&#39;s Pants ",
                     "long_description":"ignored","category":"APPAREL","merch_class":0,
                     "channel_restriction":"MULTI-CHANNEL","barcode":"123","brand_name":"Haggar",
                     "age_restriction":0,"new_field_we_dont_know":"ignored",
                     "image_data":{"primary":"GUEST_abc","alternate":"GUEST_def",
                                   "base_url":"https://imagetarget.com/image/Target/"}}
                    """)));

    ItemDetails item = items.findItem(TEE).orElseThrow();

    assertThat(item.name()).isEqualTo("Haggar H26® Men's Pants");
    assertThat(item.brand()).isEqualTo("Haggar");
    assertThat(item.imageUrl()).isEqualTo("https://imagetarget.com/image/Target/GUEST_abc");
    assertThat(item.channelRestriction()).isEqualTo("MULTI-CHANNEL");
    assertThat(item.ageRestriction()).isZero();
  }

  @Test
  void mapsPriceAsExactDecimals() {
    wireMock.stubFor(
        get(PRICE_URL)
            .willReturn(
                okJson(
                    """
                    {"item_id":"54276335","regular":17.61,"sale":12.39,"price_type":"SALE"}
                    """)));

    ItemPrice price = pricing.findPrice(TEE).orElseThrow();

    assertThat(price.regular()).isEqualByComparingTo(new BigDecimal("17.61"));
    assertThat(price.sale()).isEqualByComparingTo(new BigDecimal("12.39"));
    assertThat(price.priceType()).isEqualTo("SALE");
  }

  @Test
  void mapsAvailability() {
    wireMock.stubFor(
        get(AVAILABILITY_URL)
            .willReturn(
                okJson(
                    """
                    {"item_id":"54276335","available_units":312,"limited_quantity_threshold":20}
                    """)));

    ItemAvailability result = availability.findAvailability(TEE).orElseThrow();

    assertThat(result.availableUnits()).isEqualTo(312);
    assertThat(result.limitedQuantityThreshold()).isEqualTo(20);
  }

  // --- 404 is an answer ----------------------------------------------------------------------

  @Test
  void notFoundIsEmptyAndIsNeitherRetriedNorCountedAsFailure() {
    wireMock.stubFor(
        get(ITEM_URL)
            .willReturn(
                notFound()
                    .withHeader("Content-Type", "application/json")
                    .withBody("{\"status\":404,\"message\":\"Item not found with id 54276335\"}")));

    for (int i = 0; i < 12; i++) {
      assertThat(items.findItem(TEE)).isEmpty();
    }

    wireMock.verify(12, getRequestedFor(urlEqualTo(ITEM_URL)));
    assertThat(breakers.circuitBreaker("item").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
  }

  // --- retry ---------------------------------------------------------------------------------

  @Test
  void retriesOnceAfterServerErrorAndSucceeds() {
    wireMock.stubFor(
        get(PRICE_URL)
            .inScenario("flaky")
            .whenScenarioStateIs(STARTED)
            .willReturn(serverError())
            .willSetStateTo("recovered"));
    wireMock.stubFor(
        get(PRICE_URL)
            .inScenario("flaky")
            .whenScenarioStateIs("recovered")
            .willReturn(
                okJson(
                    """
                    {"item_id":"54276335","regular":5.49,"sale":5.49,"price_type":"REGULAR"}
                    """)));

    assertThat(pricing.findPrice(TEE)).isPresent();
    wireMock.verify(2, getRequestedFor(urlEqualTo(PRICE_URL)));
  }

  @Test
  void serverErrorTwiceIsDependencyUnavailable() {
    wireMock.stubFor(get(AVAILABILITY_URL).willReturn(serverError()));

    assertThatThrownBy(() -> availability.findAvailability(TEE))
        .isInstanceOf(DependencyUnavailableException.class)
        .extracting(e -> ((DependencyUnavailableException) e).dependency())
        .isEqualTo("availability");
    wireMock.verify(2, getRequestedFor(urlEqualTo(AVAILABILITY_URL)));
  }

  @Test
  void otherClientErrorsAreNotRetried() {
    wireMock.stubFor(get(PRICE_URL).willReturn(aResponse().withStatus(400)));

    assertThatThrownBy(() -> pricing.findPrice(TEE))
        .isInstanceOf(DependencyUnavailableException.class);
    wireMock.verify(1, getRequestedFor(urlEqualTo(PRICE_URL)));
  }

  @Test
  void malformedResponseIsDependencyUnavailable() {
    wireMock.stubFor(get(PRICE_URL).willReturn(okJson("{not json")));

    assertThatThrownBy(() -> pricing.findPrice(TEE))
        .isInstanceOf(DependencyUnavailableException.class);
  }

  // --- timeouts ------------------------------------------------------------------------------

  @Test
  void slowServiceTimesOutWithinTheViewBudget() {
    wireMock.stubFor(
        get(PRICE_URL)
            .willReturn(
                okJson("{\"item_id\":\"54276335\",\"regular\":1,\"sale\":1}")
                    .withFixedDelay(2_000)));

    Instant start = Instant.now();
    assertThatThrownBy(() -> pricing.findPrice(TEE))
        .isInstanceOf(DependencyUnavailableException.class);
    Duration elapsed = Duration.between(start, Instant.now());

    // 400ms read timeout, 50ms wait, 400ms retry: well under the 1s per-lookup budget.
    assertThat(elapsed).isBetween(Duration.ofMillis(800), Duration.ofMillis(1_000));
    wireMock.verify(2, getRequestedFor(urlEqualTo(PRICE_URL)));
  }

  // --- circuit breaker -----------------------------------------------------------------------

  @Test
  void breakerOpensAfterRepeatedFailuresAndThenFailsFastWithoutCalling() {
    wireMock.stubFor(get(AVAILABILITY_URL).willReturn(serverError()));

    for (int i = 0; i < 10; i++) { // minimum-number-of-calls, all failing
      assertThatThrownBy(() -> availability.findAvailability(TEE))
          .isInstanceOf(DependencyUnavailableException.class);
    }
    assertThat(breakers.circuitBreaker("availability").getState())
        .isEqualTo(CircuitBreaker.State.OPEN);

    assertThatThrownBy(() -> availability.findAvailability(TEE))
        .isInstanceOf(DependencyUnavailableException.class)
        .hasMessageContaining("circuit breaker is open");
    // 10 calls x 2 attempts; the call made while open never reached the service.
    wireMock.verify(20, getRequestedFor(urlEqualTo(AVAILABILITY_URL)));
  }

  @Test
  void breakersAreIndependentPerService() {
    wireMock.stubFor(get(AVAILABILITY_URL).willReturn(serverError()));
    wireMock.stubFor(
        get(PRICE_URL)
            .willReturn(
                okJson(
                    """
                    {"item_id":"54276335","regular":5.49,"sale":5.49,"price_type":"REGULAR"}
                    """)));
    for (int i = 0; i < 10; i++) {
      assertThatThrownBy(() -> availability.findAvailability(TEE));
    }

    assertThat(breakers.circuitBreaker("availability").getState())
        .isEqualTo(CircuitBreaker.State.OPEN);
    assertThat(pricing.findPrice(TEE)).isPresent();
  }
}
