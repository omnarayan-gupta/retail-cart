package com.target.retailcart.app.api.dto;

import com.target.retailcart.app.application.view.CartView;
import com.target.retailcart.app.application.view.CartViewLine;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/** The priced cart returned by GET /carts/{cartId}. */
public record CartResponse(
    String cartId,
    List<Line> items,
    int itemCount,
    BigDecimal subtotal,
    String currency,
    boolean totalIncomplete,
    List<String> warnings,
    Instant pricedAt) {

  public record Line(
      String itemId,
      String name,
      String brand,
      String imageUrl,
      int quantity,
      BigDecimal unitPrice,
      BigDecimal regularPrice,
      String priceType,
      BigDecimal lineTotal,
      String availability,
      Integer maxAllowed,
      Integer ageRestriction,
      String channelRestriction,
      String status) {

    static Line from(CartViewLine line) {
      return new Line(
          line.itemId().value(),
          line.name(),
          line.brand(),
          line.imageUrl(),
          line.quantity(),
          line.unitPrice(),
          line.regularPrice(),
          line.priceType(),
          line.lineTotal(),
          line.availability().name(),
          line.maxAllowed(),
          line.ageRestriction(),
          line.channelRestriction(),
          line.status().name());
    }
  }

  public static CartResponse from(CartView view) {
    return new CartResponse(
        view.cartId().toString(),
        view.items().stream().map(Line::from).toList(),
        view.itemCount(),
        view.subtotal(),
        view.currency(),
        view.totalIncomplete(),
        view.warnings(),
        view.pricedAt());
  }
}
