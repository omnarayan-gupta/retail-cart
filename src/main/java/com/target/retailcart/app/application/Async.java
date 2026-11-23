package com.target.retailcart.app.application;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

final class Async {

  private Async() {}

  /** Joins a future and rethrows the original runtime exception instead of a wrapper. */
  static <T> T await(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw e;
    }
  }
}
