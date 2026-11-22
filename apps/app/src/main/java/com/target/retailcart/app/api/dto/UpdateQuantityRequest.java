package com.target.retailcart.app.api.dto;

import com.target.retailcart.app.api.ApiConstraints;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Sets an absolute quantity (D18). Use DELETE to remove an item, so 0 is rejected. */
public record UpdateQuantityRequest(
    @NotNull @Min(1) @Max(ApiConstraints.MAX_QUANTITY) Integer quantity) {}
