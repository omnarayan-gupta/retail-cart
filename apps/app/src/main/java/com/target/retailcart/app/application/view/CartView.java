package com.target.retailcart.app.application.view;

import com.target.retailcart.app.domain.CartId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The priced cart, assembled live on every read (D7).
 *
 * @param subtotal sum of line totals for lines that are available and priced
 * @param totalIncomplete true when some line that might count could not be priced or checked
 *     (dependency failure), so the subtotal is not the full picture (D11)
 * @param pricedAt when prices were fetched, so "always the latest price" is visible
 */
public record CartView(
    CartId cartId,
    List<CartViewLine> items,
    int itemCount,
    BigDecimal subtotal,
    String currency,
    boolean totalIncomplete,
    List<String> warnings,
    Instant pricedAt) {}
