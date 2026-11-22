package com.target.retailcart.app.infrastructure.config;

import com.target.retailcart.app.application.view.CartViewSettings;
import com.target.retailcart.app.domain.CartExpiryPolicy;
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
   */
  @Bean(destroyMethod = "close")
  ExecutorService productCallExecutor() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }

  @Bean
  CartExpiryPolicy cartExpiryPolicy(CartProperties properties) {
    return new CartExpiryPolicy(properties.ttl(), properties.emptyTtl());
  }

  @Bean
  CartViewSettings cartViewSettings(CartProperties properties) {
    return new CartViewSettings(properties.currency(), properties.view().lookupTimeout());
  }
}
