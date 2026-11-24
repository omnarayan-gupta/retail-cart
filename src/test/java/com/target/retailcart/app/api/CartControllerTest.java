package com.target.retailcart.app.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.target.retailcart.app.application.CartItemResult;
import com.target.retailcart.app.application.CartNotFoundException;
import com.target.retailcart.app.application.CartService;
import com.target.retailcart.app.application.ConcurrentCartModificationException;
import com.target.retailcart.app.application.ItemNotFoundException;
import com.target.retailcart.app.application.ItemUnavailableException;
import com.target.retailcart.app.application.idempotency.IdempotencyKeyInProgressException;
import com.target.retailcart.app.application.idempotency.IdempotencyKeyReusedException;
import com.target.retailcart.app.application.idempotency.IdempotencyService;
import com.target.retailcart.app.application.idempotency.IdempotentOutcome;
import com.target.retailcart.app.application.view.AvailabilityStatus;
import com.target.retailcart.app.application.view.CartView;
import com.target.retailcart.app.application.view.CartViewLine;
import com.target.retailcart.app.application.view.CartViewService;
import com.target.retailcart.app.application.view.LineStatus;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.ItemId;
import com.target.retailcart.app.domain.ItemNotInCartException;
import com.target.retailcart.app.domain.QuantityLimitExceededException;
import com.target.retailcart.app.domain.port.DependencyUnavailableException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CartController.class)
class CartControllerTest {

  private static final CartId CART = CartId.of("6f1c2d3e-4a5b-4c6d-8e7f-001122334455");
  private static final ItemId TEE = ItemId.of("54276335");
  private static final String CARTS = "/api/v1/carts";

  @Autowired MockMvc mvc;
  @MockitoBean CartService cartService;
  @MockitoBean CartViewService cartViewService;
  @MockitoBean IdempotencyService idempotency;

  /** By default the idempotency layer just runs the request, as it does without a key. */
  @BeforeEach
  @SuppressWarnings("unchecked")
  void idempotencyRunsTheRequest() {
    when(idempotency.execute(any(), any(), any()))
        .thenAnswer(call ->
            new IdempotentOutcome(((Supplier<CartItemResult>) call.getArgument(2)).get(), false));
  }

  @Test
  void idempotencyKeyIsPassedThroughWithARequestDescription() throws Exception {
    when(cartService.addItemToNewCart(TEE, 2))
        .thenReturn(new CartItemResult(CART, TEE, 2, 2, true));

    mvc.perform(
            post(CARTS + "/items")
                .header("Idempotency-Key", "3f2b8c1e-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addBody(2)))
        .andExpect(status().isCreated())
        .andExpect(header().doesNotExist("Idempotent-Replayed"));

    verify(idempotency)
        .execute(eq("3f2b8c1e-key"), eq("POST /api/v1/carts/items 54276335 x2"), any());
  }

  @Test
  void replayedAddReturnsTheOriginalResponseAndSaysSo() throws Exception {
    doReturn(new IdempotentOutcome(new CartItemResult(CART, TEE, 2, 2, true), true))
        .when(idempotency)
        .execute(eq("key-1"), any(), any());

    mvc.perform(
            post(CARTS + "/items")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addBody(2)))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/api/v1/carts/" + CART))
        .andExpect(header().string("Idempotent-Replayed", "true"))
        .andExpect(jsonPath("$.cartId").value(CART.toString()));
    verifyNoInteractions(cartService);
  }

  @Test
  void invalidIdempotencyKeyIs400() throws Exception {
    mvc.perform(
            post(CARTS + "/" + CART + "/items")
                .header("Idempotency-Key", "has spaces")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addBody(1)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors", hasItem(containsString("idempotencyKey"))));
  }

  @Test
  void reusedIdempotencyKeyIs422() throws Exception {
    doThrow(new IdempotencyKeyReusedException()).when(idempotency).execute(any(), any(), any());

    mvc.perform(
            post(CARTS + "/" + CART + "/items")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addBody(1)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
  }

  @Test
  void idempotencyKeyInProgressIs409WithRetryAfter() throws Exception {
    doThrow(new IdempotencyKeyInProgressException()).when(idempotency).execute(any(), any(), any());

    mvc.perform(
            post(CARTS + "/items")
                .header("Idempotency-Key", "key-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addBody(1)))
        .andExpect(status().isConflict())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_IN_PROGRESS"));
  }

  @Test
  void firstAddCreatesCartAndReturns201WithLocation() throws Exception {
    when(cartService.addItemToNewCart(TEE, 2))
        .thenReturn(new CartItemResult(CART, TEE, 2, 2, true));

    mvc.perform(post(CARTS + "/items").contentType(MediaType.APPLICATION_JSON).content(addBody(2)))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "http://localhost/api/v1/carts/" + CART))
        .andExpect(jsonPath("$.cartId").value(CART.toString()))
        .andExpect(jsonPath("$.itemId").value("54276335"))
        .andExpect(jsonPath("$.quantity").value(2))
        .andExpect(jsonPath("$.itemCount").value(2));
  }

  @Test
  void addToExistingCartReturns200() throws Exception {
    when(cartService.addItem(CART, TEE, 1)).thenReturn(new CartItemResult(CART, TEE, 3, 5, false));

    mvc.perform(
            post(CARTS + "/" + CART + "/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content(addBody(1)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quantity").value(3))
        .andExpect(jsonPath("$.itemCount").value(5));
  }

  @Test
  void updateSetsAbsoluteQuantity() throws Exception {
    when(cartService.updateQuantity(CART, TEE, 4))
        .thenReturn(new CartItemResult(CART, TEE, 4, 4, false));

    mvc.perform(
            patch(CARTS + "/" + CART + "/items/" + TEE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":4}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.quantity").value(4));
  }

  @Test
  void deleteReturns204() throws Exception {
    mvc.perform(delete(CARTS + "/" + CART + "/items/" + TEE)).andExpect(status().isNoContent());

    verify(cartService).removeItem(CART, TEE);
  }

  @Test
  void viewReturnsPricedCart() throws Exception {
    var line =
        new CartViewLine(
            TEE,
            "Hanes Tee",
            "Hanes",
            "https://img/x",
            2,
            new BigDecimal("12.39"),
            new BigDecimal("17.61"),
            "SALE",
            new BigDecimal("24.78"),
            AvailabilityStatus.AVAILABLE,
            20,
            0,
            "MULTI-CHANNEL",
            LineStatus.OK);
    when(cartViewService.view(CART))
        .thenReturn(
            new CartView(
                CART,
                List.of(line),
                2,
                new BigDecimal("24.78"),
                "USD",
                false,
                List.of(),
                Instant.parse("2026-11-22T10:00:00Z")));

    mvc.perform(get(CARTS + "/" + CART))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.items[0].unitPrice").value(12.39))
        .andExpect(jsonPath("$.items[0].availability").value("AVAILABLE"))
        .andExpect(jsonPath("$.items[0].status").value("OK"))
        .andExpect(jsonPath("$.subtotal").value(24.78))
        .andExpect(jsonPath("$.currency").value("USD"))
        .andExpect(jsonPath("$.totalIncomplete").value(false))
        .andExpect(jsonPath("$.pricedAt").value("2026-11-22T10:00:00Z"));
  }

  // --- validation (400) ---

  @Test
  void rejectsZeroQuantity() throws Exception {
    mvc.perform(post(CARTS + "/items").contentType(MediaType.APPLICATION_JSON).content(addBody(0)))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
        .andExpect(jsonPath("$.errors", hasItem(containsString("quantity"))));
    verifyNoInteractions(cartService);
  }

  @Test
  void rejectsNonNumericItemIdInBody() throws Exception {
    mvc.perform(
            post(CARTS + "/items")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"itemId\":\"abc\",\"quantity\":1}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors", hasItem(containsString("itemId"))));
  }

  @Test
  void rejectsNonNumericItemIdInPath() throws Exception {
    mvc.perform(delete(CARTS + "/" + CART + "/items/abc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    verifyNoInteractions(cartService);
  }

  @Test
  void rejectsCartIdThatIsNotAUuid() throws Exception {
    mvc.perform(get(CARTS + "/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  @Test
  void rejectsMalformedJson() throws Exception {
    mvc.perform(post(CARTS + "/items").contentType(MediaType.APPLICATION_JSON).content("{oops"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
  }

  // --- business errors ---

  @Test
  void unknownCartIs404() throws Exception {
    when(cartViewService.view(CART)).thenThrow(new CartNotFoundException(CART));

    mvc.perform(get(CARTS + "/" + CART))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.code").value("CART_NOT_FOUND"))
        .andExpect(jsonPath("$.status").value(404));
  }

  @Test
  void itemNotInCartIs404() throws Exception {
    doThrow(new ItemNotInCartException(TEE)).when(cartService).removeItem(CART, TEE);

    mvc.perform(delete(CARTS + "/" + CART + "/items/" + TEE))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ITEM_NOT_IN_CART"));
  }

  @Test
  void unknownItemIs422() throws Exception {
    when(cartService.addItemToNewCart(any(), anyInt())).thenThrow(new ItemNotFoundException(TEE));

    mvc.perform(post(CARTS + "/items").contentType(MediaType.APPLICATION_JSON).content(addBody(1)))
        .andExpect(status().isUnprocessableContent())
        .andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"));
  }

  @Test
  void unavailableItemIs409() throws Exception {
    when(cartService.addItemToNewCart(any(), anyInt()))
        .thenThrow(new ItemUnavailableException(TEE));

    mvc.perform(post(CARTS + "/items").contentType(MediaType.APPLICATION_JSON).content(addBody(1)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ITEM_UNAVAILABLE"));
  }

  @Test
  void overLimitIs409WithMaxAllowed() throws Exception {
    when(cartService.addItemToNewCart(any(), anyInt()))
        .thenThrow(new QuantityLimitExceededException(TEE, 25, 20));

    mvc.perform(post(CARTS + "/items").contentType(MediaType.APPLICATION_JSON).content(addBody(25)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("QUANTITY_LIMIT_EXCEEDED"))
        .andExpect(jsonPath("$.maxAllowed").value(20))
        .andExpect(jsonPath("$.requestedQuantity").value(25));
  }

  @Test
  void concurrentModificationIs409() throws Exception {
    when(cartService.updateQuantity(CART, TEE, 2))
        .thenThrow(new ConcurrentCartModificationException(CART, null));

    mvc.perform(
            patch(CARTS + "/" + CART + "/items/" + TEE)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"quantity\":2}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
  }

  @Test
  void dependencyOutageOnWriteIs503WithRetryAfter() throws Exception {
    when(cartService.addItemToNewCart(any(), anyInt()))
        .thenThrow(new DependencyUnavailableException("availability", "timeout", null));

    mvc.perform(post(CARTS + "/items").contentType(MediaType.APPLICATION_JSON).content(addBody(1)))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "5"))
        .andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"))
        .andExpect(jsonPath("$.dependency").value("availability"));
  }

  @Test
  void unexpectedErrorsHideInternals() throws Exception {
    when(cartViewService.view(CART)).thenThrow(new IllegalStateException("secret db password"));

    mvc.perform(get(CARTS + "/" + CART))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
        .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
  }

  private static String addBody(int quantity) {
    return "{\"itemId\":\"54276335\",\"quantity\":" + quantity + "}";
  }
}
