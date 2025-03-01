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

package com.github.mizosoft.methanol.internal.spi;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.concurrent.Lazy;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.*;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;

/** An object that loads {@literal &} caches service providers. */
public final class ServiceProviders<S> {
  private static final Logger logger = System.getLogger(ServiceProviders.class.getName());

  private final Lazy<List<S>> providers;

  public ServiceProviders(Class<S> service) {
    requireNonNull(service);
    this.providers = Lazy.of(() -> loadProviders(service));
  }

  public List<S> get() {
    return providers.get();
  }

  private static <U> List<U> loadProviders(Class<U> service) {
    return ServiceLoader.load(service, service.getClassLoader()).stream()
        .map(ServiceProviders::tryGet)
        .filter(Objects::nonNull)
        .collect(Collectors.toUnmodifiableList());
  }

  private static <U> @Nullable U tryGet(ServiceLoader.Provider<U> provider) {
    try {
      return provider.get();
    } catch (ServiceConfigurationError error) {
      logger.log(
          Level.WARNING,
          "Provider <" + provider.type() + "> will be ignored as it couldn't be instantiated",
          error);
      return null;
    }
  }
}
