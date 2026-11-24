package com.target.retailcart.app.infrastructure.client;

import java.net.http.HttpClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** One shared {@link RestClient} for the three product services, which live in one container. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProductServiceProperties.class)
class ProductClientConfig {

  /**
   * Starts from Boot's {@link RestClient.Builder}, which already has Jackson and Micrometer
   * observation (per-call latency metrics and trace propagation) wired in (D27).
   */
  @Bean
  RestClient productRestClient(RestClient.Builder builder, ProductServiceProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .build();
    var requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());

    return builder.baseUrl(properties.baseUrl()).requestFactory(requestFactory).build();
  }
}
