package com.target.retailcart.app.domain.port;

/**
 * A dependent service failed, timed out, or its circuit breaker is open. Writes that need the
 * answer fail closed with 503; cart views degrade per line instead (D11).
 */
public class DependencyUnavailableException extends RuntimeException {

  private final String dependency;

  public DependencyUnavailableException(String dependency, String message, Throwable cause) {
    super(dependency + " service unavailable: " + message, cause);
    this.dependency = dependency;
  }

  public String dependency() {
    return dependency;
  }
}
