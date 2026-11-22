package com.target.retailcart.app.api;

import com.target.retailcart.app.api.dto.AddItemRequest;
import com.target.retailcart.app.api.dto.CartItemResponse;
import com.target.retailcart.app.api.dto.CartResponse;
import com.target.retailcart.app.api.dto.UpdateQuantityRequest;
import com.target.retailcart.app.application.CartItemResult;
import com.target.retailcart.app.application.CartService;
import com.target.retailcart.app.application.view.CartViewService;
import com.target.retailcart.app.domain.CartId;
import com.target.retailcart.app.domain.ItemId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Cart REST API (D16). Writes return a lightweight line summary (D17); only GET builds the priced
 * cart. A malformed cartId (not a UUID) is a 400; an unknown or expired one is a 404 (D5).
 */
@RestController
@RequestMapping("/api/v1/carts")
public class CartController {

  private final CartService cartService;
  private final CartViewService cartViewService;

  public CartController(CartService cartService, CartViewService cartViewService) {
    this.cartService = cartService;
    this.cartViewService = cartViewService;
  }

  /** First add: creates the cart (D3, D4). 201 with the new cart's URL in Location. */
  @PostMapping("/items")
  public ResponseEntity<CartItemResponse> addItemToNewCart(
      @Valid @RequestBody AddItemRequest request) {
    CartItemResult result =
        cartService.addItemToNewCart(ItemId.of(request.itemId()), request.quantity());
    URI location =
        ServletUriComponentsBuilder.fromCurrentContextPath()
            .path("/api/v1/carts/{cartId}")
            .buildAndExpand(result.cartId())
            .toUri();
    return ResponseEntity.created(location).body(CartItemResponse.from(result));
  }

  /** Adds to an existing cart; merges with an existing line (D9). */
  @PostMapping("/{cartId}/items")
  public CartItemResponse addItem(
      @PathVariable UUID cartId, @Valid @RequestBody AddItemRequest request) {
    return CartItemResponse.from(
        cartService.addItem(new CartId(cartId), ItemId.of(request.itemId()), request.quantity()));
  }

  /** Sets an absolute quantity (D18). */
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
  @GetMapping("/{cartId}")
  public CartResponse viewCart(@PathVariable UUID cartId) {
    return CartResponse.from(cartViewService.view(new CartId(cartId)));
  }
}
