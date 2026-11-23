package com.target.retailcart.app.api.dto;

import com.target.retailcart.app.api.ApiConstraints;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Body of the add-item endpoints. The real per-item limit comes from the Availability service
 * (D19); {@code @Max} is only a sanity ceiling against absurd input.
 */
public record AddItemRequest(
    @NotBlank
        @Pattern(regexp = ApiConstraints.ITEM_ID_PATTERN, message = ApiConstraints.ITEM_ID_MESSAGE)
        String itemId,
    @NotNull @Min(1) @Max(ApiConstraints.MAX_QUANTITY) Integer quantity) {}
