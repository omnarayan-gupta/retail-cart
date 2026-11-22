package com.target.retailcart.app.application.view;

import com.target.retailcart.app.domain.ItemId;
import java.math.BigDecimal;

/**
 * One line of the priced cart. Fields from a dependency that failed are null, and {@link #status}
 * says why, so clients never have to guess.
 */
public record CartViewLine(
    ItemId itemId,
    String name,
    String brand,
    String imageUrl,
    int quantity,
    BigDecimal unitPrice,
    BigDecimal regularPrice,
    String priceType,
    BigDecimal lineTotal,
    AvailabilityStatus availability,
    Integer maxAllowed,
    Integer ageRestriction,
    String channelRestriction,
    LineStatus status) {}
