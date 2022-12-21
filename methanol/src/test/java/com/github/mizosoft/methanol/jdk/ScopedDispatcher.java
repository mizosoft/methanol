/*
 * Copyright (c) 2022 Moataz Abdelnasser
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol.jdk;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.RecordedRequest;
import org.jetbrains.annotations.NotNull;

/**
 * A {@code Dispatcher} that delegates to other dispatchers each configured within a URI scope. The
 * dispatcher is selected if its scope is the longest prefix of request's URI.
 */
class ScopedDispatcher extends Dispatcher {
  private final Map<String, Dispatcher> dispatchers = new HashMap<>();

  ScopedDispatcher() {}

  void put(String path, Dispatcher dispatcher) {
    dispatchers.put(path, dispatcher);
  }

  private Dispatcher get(String requestedPath) {
    return dispatchers.entrySet().stream()
        .filter(entry -> requestedPath.startsWith(entry.getKey()))
        .max(Comparator.comparingInt(entry -> entry.getKey().length())) // Get max matching prefix
        .orElseThrow(() -> new IllegalStateException("unregistered scope for: " + requestedPath))
        .getValue();
  }

  @NotNull
  @Override
  public MockResponse dispatch(@NotNull RecordedRequest recordedRequest)
      throws InterruptedException {
    return get(recordedRequest.getRequestUrl().encodedPath()).dispatch(recordedRequest);
  }
}
