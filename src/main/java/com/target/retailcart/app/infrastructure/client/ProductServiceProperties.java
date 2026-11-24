package com.target.retailcart.app.infrastructure.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Connection settings for the Target product services (prefix {@code product-service}).
 *
 * <p>Timeouts are sized so one call plus one retry stays inside the cart view's per-lookup budget
 * ({@code cart.view.lookup-timeout}, 1s): 400ms + 50ms wait + 400ms = 850ms (D15).
 *
 * @param baseUrl scheme, host and port of the product container, e.g. {@code http://localhost:8080}
 * @param connectTimeout time allowed to open a TCP connection
 * @param readTimeout time allowed to wait for the response
 */
@ConfigurationProperties("product-service")
public record ProductServiceProperties(
    @DefaultValue("http://localhost:8080") String baseUrl,
    @DefaultValue("250ms") Duration connectTimeout,
    @DefaultValue("400ms") Duration readTimeout) {}
