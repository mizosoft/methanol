package com.github.mizosoft.methanol.internal.function;

import java.util.concurrent.CompletionException;

/** A {@code Runnable} that may throw a checked exception. */
@FunctionalInterface
public interface ThrowingRunnable {
  void run() throws Exception;

  default Runnable toUnchecked() {
    return () -> {
      try {
        run();
      } catch (Exception e) {
        Unchecked.propagateIfUnchecked(e);
        throw new CompletionException(e);
      }
    };
  }
}
