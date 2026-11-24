package com.target.retailcart.app.application.idempotency;

import java.time.Duration;

/**
 * @param ttl how long a completed key is remembered (24h)
 * @param lease how long an in-progress claim blocks other requests before it counts as abandoned
 */
public record IdempotencySettings(Duration ttl, Duration lease) {}
