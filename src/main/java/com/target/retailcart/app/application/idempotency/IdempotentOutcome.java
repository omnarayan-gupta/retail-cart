package com.target.retailcart.app.application.idempotency;

import com.target.retailcart.app.application.CartItemResult;

/**
 * @param result the write's result, either fresh or the stored original
 * @param replayed true when this is a repeat of an earlier request and nothing was executed
 */
public record IdempotentOutcome(CartItemResult result, boolean replayed) {}
