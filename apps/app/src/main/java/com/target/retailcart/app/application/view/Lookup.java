package com.target.retailcart.app.application.view;

import java.util.Optional;

/**
 * Outcome of one dependency call during a cart view. Separates "the service said not found" from
 * "the service failed", because the cart reacts differently to each (D11).
 */
sealed interface Lookup<T> {

  record Found<T>(T value) implements Lookup<T> {}

  record NotFound<T>() implements Lookup<T> {}

  record Failed<T>() implements Lookup<T> {}

  static <T> Lookup<T> of(Optional<T> result) {
    return result.<Lookup<T>>map(Found::new).orElseGet(NotFound::new);
  }

  default Optional<T> found() {
    return this instanceof Found<T>(T value) ? Optional.of(value) : Optional.empty();
  }
}
