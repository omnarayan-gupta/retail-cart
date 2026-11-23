package com.target.retailcart.app.domain.product;

import com.target.retailcart.app.domain.ItemId;

/** Current availability of an item as returned by the Availability service. */
public record ItemAvailability(ItemId itemId, int availableUnits, int limitedQuantityThreshold) {}
