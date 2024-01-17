/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.function.Supplier;

/** Utility class for loading/caching service providers. */
public final class ServiceCache<S> {
  private static final Logger logger = System.getLogger(ServiceCache.class.getName());

  private final Supplier<List<S>> providers;

  public ServiceCache(Class<S> service) {
    requireNonNull(service);
    providers = Lazy.of(() -> loadProviders(service));
  }

  public List<S> getProviders() {
    return providers.get();
  }

  private static <U> List<U> loadProviders(Class<U> service) {
    var providers = new ArrayList<U>();
    ServiceLoader.load(service, service.getClassLoader()).stream()
        .forEach(provider -> addProviderLenient(provider, providers));
    return Collections.unmodifiableList(providers);
  }

  private static <U> void addProviderLenient(ServiceLoader.Provider<U> provider, List<U> providers) {
    try {
      providers.add(provider.get());
    } catch (ServiceConfigurationError error) {
      logger.log(
          Level.WARNING,
          "provider <" + provider.type() + "> will be ignored as it couldn't be instantiated",
          error);
    }
  }
}
