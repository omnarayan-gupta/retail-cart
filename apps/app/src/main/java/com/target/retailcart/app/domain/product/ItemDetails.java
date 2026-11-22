package com.target.retailcart.app.domain.product;

import com.target.retailcart.app.domain.ItemId;

/**
 * Catalog details the cart shows for an item. Fetched live on every cart view, never stored (D7).
 * Age and channel restrictions are shown as flags but not enforced yet (D31, clarifying question
 * 3).
 */
public record ItemDetails(
    ItemId itemId,
    String name,
    String brand,
    String imageUrl,
    int ageRestriction,
    String channelRestriction) {}
