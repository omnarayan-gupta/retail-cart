package com.target.retailcart.app.domain.product;

import com.target.retailcart.app.domain.ItemId;
import java.math.BigDecimal;

/** Current price of an item as returned by the Pricing service. Never stored or cached (D7). */
public record ItemPrice(ItemId itemId, BigDecimal regular, BigDecimal sale, String priceType) {}
