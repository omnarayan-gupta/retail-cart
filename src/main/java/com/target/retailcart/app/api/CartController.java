package com.target.retailcart.app.api;

import com.target.retailcart.app.api.dto.AddItemRequest;
import com.target.retailcart.app.api.dto.CartItemResponse;
import com.target.retailcart.app.api.dto.CartResponse;
import com.target.retailcart.app.api.dto.UpdateQuantityRequest;
import com.target.retailcart.app.application.CartItemResult;
import com.target.retailcart.app.application.CartService;
import com.target.retailcart.app.application.idempotency.IdempotencyService;
import com.target.retailcart.app.application.idempotency.IdempotentOutcome;
import com.target.retailcart.app.application.view.CartViewService;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.ItemId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Cart REST API (D16). Writes return a lightweight line summary (D17); only GET builds the priced
 * cart. A malformed cartId (not a UUID) is a 400; an unknown or expired one is a 404 (D5).
 */
@Tag(name = "Carts")
@RestController
@RequestMapping("/api/v1/carts")
public class CartController {

  /** Optional on both adds; a repeat with the same key returns the original result (D6). */
  public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

  /** Set to {@code true} on a response that replays an earlier request instead of running it. */
  public static final String IDEMPOTENT_REPLAYED = "Idempotent-Replayed";

  /** Visible ASCII only, so keys are safe to log and store. */
  private static final String IDEMPOTENCY_KEY_PATTERN = "^[\\x21-\\x7E]+$";

  private static final String IDEMPOTENCY_KEY_MESSAGE =
      "must be 1-255 visible ASCII characters, e.g. a UUID";

  private final CartService cartService;
  private final CartViewService cartViewService;
  private final IdempotencyService idempotency;

  public CartController(
      CartService cartService, CartViewService cartViewService, IdempotencyService idempotency) {
    this.cartService = cartService;
    this.cartViewService = cartViewService;
    this.idempotency = idempotency;
  }

  /** First add: creates the cart (D3, D4). 201 with the new cart's URL in Location. */
  @Operation(
      summary = "Add an item to a new cart",
      description =
          "Creates the cart and adds the item; returns 201 with the cart URL in Location. Only"
              + " available items can be added, up to the item's purchase limit.")
  @PostMapping("/items")
  public ResponseEntity<CartItemResponse> addItemToNewCart(
      @RequestHeader(name = IDEMPOTENCY_KEY, required = false)
          @Size(min = 1, max = 255)
          @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = IDEMPOTENCY_KEY_MESSAGE)
          String idempotencyKey,
      @Valid @RequestBody AddItemRequest request) {
    IdempotentOutcome outcome =
        idempotency.execute(
            idempotencyKey,
            "POST /api/v1/carts/items " + request.itemId() + " x" + request.quantity(),
            () -> cartService.addItemToNewCart(ItemId.of(request.itemId()), request.quantity()));
    CartItemResult result = outcome.result();
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/v1/carts/{cartId}")
            .buildAndExpand(result.cartId())
            .toUri();
    return ResponseEntity.created(location)
        .headers(replayHeader(outcome))
        .body(CartItemResponse.from(result));
  }

  /** Adds to an existing cart; merges with an existing line (D9). */
  @Operation(
      summary = "Add an item to an existing cart",
      description = "If the item is already in the cart, the quantities are added together.")
  @PostMapping("/{cartId}/items")
  public ResponseEntity<CartItemResponse> addItem(
      @PathVariable UUID cartId,
      @RequestHeader(name = IDEMPOTENCY_KEY, required = false)
          @Size(min = 1, max = 255)
          @Pattern(regexp = IDEMPOTENCY_KEY_PATTERN, message = IDEMPOTENCY_KEY_MESSAGE)
          String idempotencyKey,
      @Valid @RequestBody AddItemRequest request) {
    IdempotentOutcome outcome =
        idempotency.execute(
            idempotencyKey,
            "POST /api/v1/carts/"
                + cartId
                + "/items "
                + request.itemId()
                + " x"
                + request.quantity(),
            () ->
                cartService.addItem(
                    new CartId(cartId), ItemId.of(request.itemId()), request.quantity()));
    return ResponseEntity.ok()
        .headers(replayHeader(outcome))
        .body(CartItemResponse.from(outcome.result()));
  }

  private static HttpHeaders replayHeader(IdempotentOutcome outcome) {
    HttpHeaders headers = new HttpHeaders();
    if (outcome.replayed()) {
      headers.set(IDEMPOTENT_REPLAYED, "true");
    }
    return headers;
  }

  /** Sets an absolute quantity (D18). */
  @Operation(
      summary = "Change an item's quantity",
      description =
          "Sets the quantity (not a delta). Increases are re-checked against availability.")
  @PatchMapping("/{cartId}/items/{itemId}")
  public CartItemResponse updateQuantity(
      @PathVariable UUID cartId,
      @PathVariable
          @Pattern(
              regexp = ApiConstraints.ITEM_ID_PATTERN,
              message = ApiConstraints.ITEM_ID_MESSAGE)
          String itemId,
      @Valid @RequestBody UpdateQuantityRequest request) {
    return CartItemResponse.from(
        cartService.updateQuantity(new CartId(cartId), ItemId.of(itemId), request.quantity()));
  }

  /** Removes an item; 204 No Content. The cart stays, even if now empty (D20). */
  @Operation(summary = "Remove an item from the cart")
  @DeleteMapping("/{cartId}/items/{itemId}")
  public ResponseEntity<Void> removeItem(
      @PathVariable UUID cartId,
      @PathVariable
          @Pattern(
              regexp = ApiConstraints.ITEM_ID_PATTERN,
              message = ApiConstraints.ITEM_ID_MESSAGE)
          String itemId) {
    cartService.removeItem(new CartId(cartId), ItemId.of(itemId));
    return ResponseEntity.noContent().build();
  }

  /** The priced cart, built live with the latest prices (D7). */
  @Operation(
      summary = "View the cart",
      description =
          "Returns every line with its latest price and availability. If a product service is"
              + " down, the cart is still returned with per-line statuses and"
              + " totalIncomplete=true.")
  @GetMapping("/{cartId}")
  public CartResponse viewCart(@PathVariable UUID cartId) {
    return CartResponse.from(cartViewService.view(new CartId(cartId)));
  }
}
