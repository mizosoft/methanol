/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

/**
 * {@code Extension} that provides {@link TestSubscriber} or {@link TestSubscriberContext} instances
 * and reports misbehaving usage of the provided subscribers, according to the reactive-streams
 * protocol.
 */
public final class TestSubscriberExtension implements ParameterResolver {
  private static final Namespace EXTENSION_NAMESPACE =
      Namespace.create(TestSubscriberExtension.class);

  public TestSubscriberExtension() {}

  @Override
  public boolean supportsParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var type = parameterContext.getParameter().getType();
    return type.equals(TestSubscriber.class) || type.equals(TestSubscriberContext.class);
  }

  @Override
  public Object resolveParameter(
      ParameterContext parameterContext, ExtensionContext extensionContext)
      throws ParameterResolutionException {
    var subscribers = ManagedSubscribers.get(extensionContext);
    var type = parameterContext.getParameter().getType();
    return type.equals(TestSubscriber.class) ? subscribers.createSubscriber() : subscribers.context;
  }

  private static final class ManagedSubscribers implements CloseableResource {
    final TestSubscriberContext context = new TestSubscriberContext();

    private ManagedSubscribers() {}

    <T> TestSubscriber<T> createSubscriber() {
      return context.createSubscriber();
    }

    @Override
    public void close() throws Exception {
      context.close();
    }

    static ManagedSubscribers get(ExtensionContext extensionContext) {
      return extensionContext
          .getStore(EXTENSION_NAMESPACE)
          .getOrComputeIfAbsent(
              ManagedSubscribers.class, __ -> new ManagedSubscribers(), ManagedSubscribers.class);
    }
  }
}
