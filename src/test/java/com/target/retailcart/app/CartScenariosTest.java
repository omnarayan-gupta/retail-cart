package com.target.retailcart.app;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.wiremock.spring.ConfigureWireMock;
import org.wiremock.spring.EnableWireMock;
import org.wiremock.spring.InjectWireMock;

/**
 * The case study's rules, end to end: the real app on a real port, PostgreSQL in a container, and
 * WireMock playing the Target product services. WireMock is what lets these change prices and
 * availability between calls, or fail on demand, which the real product container can't do.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnableWireMock(
    @ConfigureWireMock(name = "product", baseUrlProperties = "product-service.base-url"))
@AutoConfigureTracing
@Testcontainers
class CartScenariosTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private static final String TEE = "54276335";
  private static final String CLEANER = "15744221";

  @LocalServerPort int port;

  @InjectWireMock("product")
  WireMockServer products;

  @Autowired CircuitBreakerRegistry breakers;
  @Autowired JdbcTemplate jdbc;

  private RestClient api;

  @BeforeEach
  void setUp() {
    products.resetAll();
    breakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
    api =
        RestClient.builder()
            .baseUrl("http://localhost:" + port + "/api/v1/carts")
            .defaultStatusHandler(status -> true, (request, response) -> {}) // assert, don't throw
            .build();
    // Default catalog: both items exist, are in stock and priced.
    item(TEE, "Hanes Sport Men's Performance");
    item(CLEANER, "Mrs. Meyer's Lemon Verbena Tub");
    price(TEE, "17.61", "12.39", "SALE");
    price(CLEANER, "5.49", "5.49", "REGULAR");
    availability(TEE, 312, 20);
    availability(CLEANER, 223, 10);
  }

  @Nested
  class OnlyAvailableItemsCanBeAdded {

    @Test
    void availableItemIsAddedAndShownInTheCart() {
      String cart = newCartWith(TEE, 2);

      String view = view(cart);
      assertThat(JsonPath.<String>read(view, "$.items[0].itemId")).isEqualTo(TEE);
      assertThat(JsonPath.<Integer>read(view, "$.items[0].quantity")).isEqualTo(2);
      assertThat(JsonPath.<String>read(view, "$.items[0].status")).isEqualTo("OK");
    }

    @Test
    void outOfStockItemIsRejectedAndTheCartIsUnchanged() {
      String cart = newCartWith(TEE, 1);
      availability(CLEANER, 0, 10);

      ResponseEntity<String> add = add(cart, CLEANER, 1);

      assertThat(add.getStatusCode().value()).isEqualTo(409);
      assertThat(JsonPath.<String>read(add.getBody(), "$.code")).isEqualTo("ITEM_UNAVAILABLE");
      assertThat(JsonPath.<List<String>>read(view(cart), "$.items[*].itemId")).containsExactly(TEE);
    }
  }

  @Nested
  class CartAlwaysShowsTheLatestPriceAndAvailability {

    @Test
    void priceChangeShowsOnTheNextView() {
      String cart = newCartWith(TEE, 2);
      assertThat(JsonPath.<Double>read(view(cart), "$.subtotal")).isEqualTo(24.78); // 2 x 12.39

      price(TEE, "17.61", "9.99", "SALE"); // the sale gets deeper

      String view = view(cart);
      assertThat(JsonPath.<Double>read(view, "$.items[0].unitPrice")).isEqualTo(9.99);
      assertThat(JsonPath.<Double>read(view, "$.subtotal")).isEqualTo(19.98);
    }

    @Test
    void itemThatSellsOutAfterBeingAddedIsFlaggedNotRemoved() {
      String cart = newCartWith(TEE, 1);
      add(cart, CLEANER, 2);

      availability(CLEANER, 0, 10); // sells out while sitting in the cart

      String view = view(cart);
      assertThat(JsonPath.<List<String>>read(view, "$.items[?(@.itemId=='15744221')].status"))
          .containsExactly("UNAVAILABLE");
      assertThat(JsonPath.<Double>read(view, "$.subtotal")).as("excluded").isEqualTo(12.39);
      assertThat(JsonPath.<Boolean>read(view, "$.totalIncomplete"))
          .as("nothing missing: the total is final for what can be bought")
          .isFalse();
    }

    @Test
    void purchaseLimitThatDropsBelowTheCartQuantityIsFlagged() {
      String cart = newCartWith(TEE, 8);

      availability(TEE, 40, 5); // stock falls, so the limit tier drops to 5

      String view = view(cart);
      assertThat(JsonPath.<String>read(view, "$.items[0].status"))
          .isEqualTo("QUANTITY_EXCEEDS_LIMIT");
      assertThat(JsonPath.<Integer>read(view, "$.items[0].maxAllowed")).isEqualTo(5);
      assertThat(JsonPath.<Integer>read(view, "$.items[0].quantity")).as("not cut").isEqualTo(8);
    }
  }

  @Nested
  class DependencyFailures {

    @Test
    void pricingOutageDegradesTheViewInsteadOfFailingIt() {
      String cart = newCartWith(TEE, 1);
      products.stubFor(get("/product/v1/prices/" + TEE).willReturn(serverError()));

      ResponseEntity<String> response =
          api.get().uri("/{cart}", cart).retrieve().toEntity(String.class);

      assertThat(response.getStatusCode().value()).isEqualTo(200);
      assertThat(JsonPath.<String>read(response.getBody(), "$.items[0].status"))
          .isEqualTo("PRICE_UNAVAILABLE");
      assertThat(JsonPath.<Object>read(response.getBody(), "$.items[0].unitPrice")).isNull();
      assertThat(JsonPath.<Boolean>read(response.getBody(), "$.totalIncomplete")).isTrue();
    }

    @Test
    void availabilityOutageMakesAddsFailClosed() {
      String cart = newCartWith(TEE, 1);
      products.stubFor(get("/product/v1/availability/" + CLEANER).willReturn(serverError()));

      ResponseEntity<String> add = add(cart, CLEANER, 1);

      assertThat(add.getStatusCode().value()).isEqualTo(503);
      assertThat(add.getHeaders().getFirst("Retry-After")).isEqualTo("5");
      assertThat(JsonPath.<String>read(add.getBody(), "$.dependency")).isEqualTo("availability");
    }
  }

  @Nested
  class SafeUnderRetriesAndConcurrency {

    @Test
    void retriedAddWithTheSameIdempotencyKeyCreatesOneCart() {
      Integer cartsBefore = jdbc.queryForObject("select count(*) from cart", Integer.class);

      ResponseEntity<String> first = addToNewCart(TEE, 2, "scenario-key-1");
      ResponseEntity<String> retry = addToNewCart(TEE, 2, "scenario-key-1");

      assertThat(retry.getStatusCode().value()).isEqualTo(201);
      assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
      assertThat(JsonPath.<String>read(retry.getBody(), "$.cartId"))
          .isEqualTo(JsonPath.<String>read(first.getBody(), "$.cartId"));
      assertThat(jdbc.queryForObject("select count(*) from cart", Integer.class))
          .isEqualTo(cartsBefore + 1);
    }

    @Test
    void concurrentAddsToOneCartLoseNoUpdates() throws Exception {
      String cart = newCartWith(TEE, 1);
      int requests = 6;
      var start = new CountDownLatch(requests);
      List<Callable<ResponseEntity<String>>> adds = new ArrayList<>();
      for (int i = 0; i < requests; i++) {
        adds.add(() -> {
          start.countDown();
          start.await();
          return add(cart, TEE, 1);
        });
      }

      List<Integer> statuses = new ArrayList<>();
      try (var executor = Executors.newFixedThreadPool(requests)) {
        for (Future<ResponseEntity<String>> f : executor.invokeAll(adds)) {
          ResponseEntity<String> response = f.get();
          statuses.add(response.getStatusCode().value());
          if (response.getStatusCode().value() == 409) {
            assertThat(JsonPath.<String>read(response.getBody(), "$.code"))
                .isEqualTo("CONCURRENT_MODIFICATION");
          }
        }
      }

      long succeeded = statuses.stream().filter(s -> s == 200).count();
      assertThat(statuses).allMatch(s -> s == 200 || s == 409);
      assertThat(succeeded).isPositive();
      assertThat(JsonPath.<Integer>read(view(cart), "$.items[0].quantity"))
          .as("every accepted add is counted exactly once")
          .isEqualTo(1 + (int) succeeded);
    }
  }

  @Test
  void callerTraceIdIsPropagatedToEveryProductServiceCall() {
    String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
    String traceparent = "00-" + traceId + "-00f067aa0ba902b7-01";
    String cart = newCartWith(TEE, 1);
    products.resetRequests();

    api.get()
        .uri("/{cart}", cart)
        .header("traceparent", traceparent)
        .retrieve()
        .toEntity(String.class);

    // The view fans out on virtual threads; each call must still belong to the caller's trace.
    for (String path : List.of("items", "prices", "availability")) {
      products.verify(
          getRequestedFor(urlEqualTo("/product/v1/" + path + "/" + TEE))
              .withHeader("traceparent", matching("00-" + traceId + "-[0-9a-f]{16}-01")));
    }
  }

  // --- helpers -----------------------------------------------------------------------------------

  private String newCartWith(String itemId, int quantity) {
    ResponseEntity<String> response = addToNewCart(itemId, quantity, null);
    assertThat(response.getStatusCode().value()).isEqualTo(201);
    return JsonPath.read(response.getBody(), "$.cartId");
  }

  private ResponseEntity<String> addToNewCart(String itemId, int quantity, String idempotencyKey) {
    var request =
        api.post()
            .uri("/items")
            .contentType(MediaType.APPLICATION_JSON)
            .body("{\"itemId\":\"%s\",\"quantity\":%d}".formatted(itemId, quantity));
    if (idempotencyKey != null) {
      request = request.header("Idempotency-Key", idempotencyKey);
    }
    return request.retrieve().toEntity(String.class);
  }

  private ResponseEntity<String> add(String cart, String itemId, int quantity) {
    return api.post()
        .uri("/{cart}/items", cart)
        .contentType(MediaType.APPLICATION_JSON)
        .body("{\"itemId\":\"%s\",\"quantity\":%d}".formatted(itemId, quantity))
        .retrieve()
        .toEntity(String.class);
  }

  private String view(String cart) {
    ResponseEntity<String> response =
        api.get().uri("/{cart}", cart).retrieve().toEntity(String.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    return response.getBody();
  }

  private void item(String id, String name) {
    products.stubFor(
        get("/product/v1/items/" + id)
            .willReturn(
                okJson(
                    """
                    {"item_id":"%s","small_description":"%s","brand_name":"Brand",
                     "channel_restriction":"MULTI-CHANNEL","age_restriction":0,
                     "image_data":{"primary":"GUEST_1","base_url":"https://img/"}}
                    """
                        .formatted(id, name))));
  }

  private void price(String id, String regular, String sale, String type) {
    products.stubFor(
        get("/product/v1/prices/" + id)
            .willReturn(
                okJson(
                    """
                    {"item_id":"%s","regular":%s,"sale":%s,"price_type":"%s"}
                    """
                        .formatted(id, regular, sale, type))));
  }

  private void availability(String id, int units, int limit) {
    products.stubFor(
        get("/product/v1/availability/" + id)
            .willReturn(
                okJson(
                    """
                    {"item_id":"%s","available_units":%d,"limited_quantity_threshold":%d}
                    """
                        .formatted(id, units, limit))));
  }
}
