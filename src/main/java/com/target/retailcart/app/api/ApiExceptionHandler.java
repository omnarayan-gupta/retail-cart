package com.target.retailcart.app.api;

import com.target.retailcart.app.application.CartNotFoundException;
import com.target.retailcart.app.application.ConcurrentCartModificationException;
import com.target.retailcart.app.application.ItemNotFoundException;
import com.target.retailcart.app.application.ItemUnavailableException;
import com.target.retailcart.app.domain.ItemNotInCartException;
import com.target.retailcart.app.domain.QuantityLimitExceededException;
import com.target.retailcart.app.domain.port.DependencyUnavailableException;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Maps every failure to RFC 7807 Problem Details with a machine-readable {@code code} (D16). Error
 * bodies never contain stack traces or internal details (D28).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(CartNotFoundException.class)
  ProblemDetail cartNotFound(CartNotFoundException e) {
    return problem(HttpStatus.NOT_FOUND, "CART_NOT_FOUND", e.getMessage());
  }

  @ExceptionHandler(ItemNotInCartException.class)
  ProblemDetail itemNotInCart(ItemNotInCartException e) {
    return problem(HttpStatus.NOT_FOUND, "ITEM_NOT_IN_CART", e.getMessage());
  }

  @ExceptionHandler(ItemNotFoundException.class)
  ProblemDetail itemNotFound(ItemNotFoundException e) {
    return problem(HttpStatus.UNPROCESSABLE_CONTENT, "ITEM_NOT_FOUND", e.getMessage());
  }

  @ExceptionHandler(ItemUnavailableException.class)
  ProblemDetail itemUnavailable(ItemUnavailableException e) {
    return problem(HttpStatus.CONFLICT, "ITEM_UNAVAILABLE", e.getMessage());
  }

  @ExceptionHandler(QuantityLimitExceededException.class)
  ProblemDetail quantityLimitExceeded(QuantityLimitExceededException e) {
    ProblemDetail problem = problem(HttpStatus.CONFLICT, "QUANTITY_LIMIT_EXCEEDED", e.getMessage());
    problem.setProperty("itemId", e.itemId().value());
    problem.setProperty("requestedQuantity", e.requestedQuantity());
    problem.setProperty("maxAllowed", e.maxAllowed());
    return problem;
  }

  @ExceptionHandler(ConcurrentCartModificationException.class)
  ProblemDetail concurrentModification(ConcurrentCartModificationException e) {
    return problem(HttpStatus.CONFLICT, "CONCURRENT_MODIFICATION", e.getMessage());
  }

  @ExceptionHandler(DependencyUnavailableException.class)
  ResponseEntity<ProblemDetail> dependencyUnavailable(DependencyUnavailableException e) {
    log.warn("Rejecting write: {}", e.getMessage());
    ProblemDetail problem =
        problem(
            HttpStatus.SERVICE_UNAVAILABLE,
            "DEPENDENCY_UNAVAILABLE",
            "A required service is temporarily unavailable; please retry");
    problem.setProperty("dependency", e.dependency());
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header(HttpHeaders.RETRY_AFTER, "5")
        .body(problem);
  }

  /** Domain guards that slipped past request validation, e.g. an oversized item id. */
  @ExceptionHandler(IllegalArgumentException.class)
  ProblemDetail invalidArgument(IllegalArgumentException e) {
    return problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", e.getMessage());
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail unexpected(Exception e) {
    log.error("Unexpected error", e);
    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
  }

  /** Invalid request body: lists every field error. */
  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<String> errors =
        e.getBindingResult().getFieldErrors().stream()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .toList();
    return validationFailed(errors, headers);
  }

  /** Invalid path variable, e.g. a non-numeric item id. */
  @Override
  protected ResponseEntity<Object> handleHandlerMethodValidationException(
      HandlerMethodValidationException e,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    List<String> errors =
        e.getParameterValidationResults().stream()
            .flatMap(result ->
                result.getResolvableErrors().stream()
                    .map(error ->
                        result.getMethodParameter().getParameterName()
                            + ": "
                            + error.getDefaultMessage()))
            .toList();
    return validationFailed(errors, headers);
  }

  /**
   * Framework-raised errors (malformed JSON, a cartId that isn't a UUID, wrong method...) get a
   * {@code code} too, so every error body has the same shape.
   */
  @Override
  protected ResponseEntity<Object> handleExceptionInternal(
      Exception e,
      @Nullable Object body,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    if (body instanceof ProblemDetail problem
        && (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
      problem.setProperty(
          "code", status.value() == 400 ? "VALIDATION_FAILED" : "HTTP_" + status.value());
    }
    return super.handleExceptionInternal(e, body, headers, status, request);
  }

  private ResponseEntity<Object> validationFailed(List<String> errors, HttpHeaders headers) {
    ProblemDetail problem =
        problem(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The request is invalid");
    problem.setProperty("errors", errors);
    return ResponseEntity.badRequest().headers(headers).body(problem);
  }

  private static ProblemDetail problem(HttpStatus status, String code, String detail) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setProperty("code", code);
    return problem;
  }
}
