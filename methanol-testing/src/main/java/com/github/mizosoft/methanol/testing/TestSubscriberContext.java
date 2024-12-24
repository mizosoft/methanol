/*
 * Copyright (c) 2024 Moataz Hussein
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

package com.github.mizosoft.methanol.testing;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Creates and manages {@link TestSubscriber} instances. */
public final class TestSubscriberContext implements AutoCloseable {
  private final List<TestSubscriber<?>> subscribers = new ArrayList<>();

  public TestSubscriberContext() {}

  public <T> TestSubscriber<T> createSubscriber() {
    var subscriber = new TestSubscriber<T>();
    subscribers.add(subscriber);
    return subscriber;
  }

  public <T, S extends TestSubscriber<T>> S createSubscriber(Supplier<S> factory) {
    var subscriber = factory.get();
    subscribers.add(subscriber);
    return subscriber;
  }

  @Override
  public void close() throws Exception {
    var allProtocolViolations = new ArrayList<Throwable>();
    for (var subscriber : subscribers) {
      var protocolViolations = subscriber.protocolViolations();
      if (!protocolViolations.isEmpty()) {
        if (protocolViolations.size() == 1) {
          allProtocolViolations.add(protocolViolations.get(0));
        } else {
          allProtocolViolations.add(
              new AggregateException("Multiple protocol violations", protocolViolations));
        }
      }
    }
    subscribers.clear();

    if (!allProtocolViolations.isEmpty()) {
      throw new AggregateException(
          "Misbehaving reactive-streams implementation", allProtocolViolations);
    }
  }
}
