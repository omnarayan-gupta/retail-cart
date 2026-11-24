package com.target.retailcart.app.infrastructure.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** API docs (D34): Swagger UI at /swagger-ui.html, OpenAPI JSON at /v3/api-docs. */
@Configuration(proxyBeanMethods = false)
class OpenApiConfig {

  @Bean
  OpenAPI cartApi() {
    return new OpenAPI()
        .info(
            new Info()
                .title("Retail Cart API")
                .version("v1")
                .description(
                    """
                    Shopping cart for Target guests: add, update, remove and view items.

                    - The first add creates the cart (`POST /api/v1/carts/items`, 201 + Location).
                    - Writes return a small summary; `GET /api/v1/carts/{cartId}` returns the cart \
                    with the latest prices and availability.
                    - Send an `Idempotency-Key` header on adds to make retries safe.
                    - Errors are RFC 7807 Problem Details with a machine-readable `code`.
                    """));
  }
}
