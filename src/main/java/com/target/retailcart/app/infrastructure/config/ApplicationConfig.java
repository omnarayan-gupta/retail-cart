package com.target.retailcart.app.infrastructure.config;

import com.target.retailcart.app.application.idempotency.IdempotencySettings;
import com.target.retailcart.app.application.view.CartViewSettings;
import com.target.retailcart.app.domain.CartExpiryPolicy;
import io.micrometer.context.ContextExecutorService;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Clock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CartProperties.class)
public class ApplicationConfig {

  /** Injected everywhere time matters, so tests can fix the clock. */
  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * Runs calls to the product services. One virtual thread per call (D14): blocking I/O is cheap,
   * so there is no pool size to tune, and timeouts in the clients bound every call.
   *
   * <p>Wrapped so each task carries the caller's context (the current trace) onto its thread:
   * without it, every fan-out call would start a new, unrelated trace (D41).
   */
  @Bean(destroyMethod = "close")
  ExecutorService productCallExecutor() {
    return ContextExecutorService.wrap(
        Executors.newVirtualThreadPerTaskExecutor(), ContextSnapshotFactory.builder().build());
  }

  @Bean
  CartExpiryPolicy cartExpiryPolicy(CartProperties properties) {
    return new CartExpiryPolicy(properties.ttl(), properties.emptyTtl());
  }

  @Bean
  CartViewSettings cartViewSettings(CartProperties properties) {
    return new CartViewSettings(properties.currency(), properties.view().lookupTimeout());
  }

  @Bean
  IdempotencySettings idempotencySettings(CartProperties properties) {
    return new IdempotencySettings(
        properties.idempotency().ttl(), properties.idempotency().lease());
  }
}
