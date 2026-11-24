package com.target.retailcart.app.infrastructure.client;

import com.target.retailcart.app.domain.port.DependencyUnavailableException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

/**
 * Runs calls to one product service with a retry inside a circuit breaker (D15).
 *
 * <ul>
 *   <li>Retry is <b>inside</b> the breaker, so a call and its retry count as one outcome.
 *   <li>A 404 is an answer, not a failure: it becomes {@link Optional#empty()}, is never retried
 *       and never trips the breaker.
 *   <li>Anything else that goes wrong (timeout, connection error, 5xx, other 4xx, bad JSON, open
 *       breaker) becomes {@link DependencyUnavailableException}: reads degrade, writes fail closed
 *       (D11).
 * </ul>
 *
 * Which exceptions are retried, and the breaker thresholds, are set per service in {@code
 * application.yml} under {@code resilience4j}.
 */
final class ResilientCall {

  private final String dependency;
  private final CircuitBreaker circuitBreaker;
  private final Retry retry;

  ResilientCall(String dependency, CircuitBreakerRegistry breakers, RetryRegistry retries) {
    this.dependency = dependency;
    this.circuitBreaker = breakers.circuitBreaker(dependency);
    this.retry = retries.retry(dependency);
  }

  /** Runs a GET whose 404 means "no such thing". */
  <T> Optional<T> find(Supplier<T> call) {
    Supplier<Optional<T>> notFoundIsEmpty =
        () -> {
          try {
            return Optional.ofNullable(call.get());
          } catch (HttpClientErrorException.NotFound e) {
            return Optional.empty();
          }
        };
    try {
      return circuitBreaker.executeSupplier(Retry.decorateSupplier(retry, notFoundIsEmpty));
    } catch (CallNotPermittedException e) {
      throw new DependencyUnavailableException(dependency, "circuit breaker is open", e);
    } catch (RestClientException e) {
      throw new DependencyUnavailableException(dependency, e.getMessage(), e);
    }
  }
}
