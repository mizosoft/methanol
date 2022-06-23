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

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.concurrent.locks.ReentrantLock;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Utility class for loading/caching service providers. */
public final class ServiceCache<S> {
  private static final Logger logger = System.getLogger(ServiceCache.class.getName());

  private final Class<S> service;
  private final ReentrantLock lock = new ReentrantLock();
  private volatile @MonotonicNonNull List<S> providers;

  public ServiceCache(Class<S> service) {
    this.service = requireNonNull(service);
  }

  public List<S> getProviders() {
    List<S> cached = providers;
    if (cached == null) {
      // Prevent getProvider() from being called from a provider constructor/method
      if (lock.isHeldByCurrentThread()) {
        throw new ServiceConfigurationError("recursive loading of providers");
      }
      lock.lock();
      try {
        cached = providers;
        if (cached == null) {
          cached = loadProviders();
          providers = cached;
        }
      } finally {
        lock.unlock();
      }
    }
    return cached;
  }

  private List<S> loadProviders() {
    List<S> providers = new ArrayList<>();
    ServiceLoader.load(service, service.getClassLoader()).stream()
        .forEach(provider -> addProviderLenient(provider, providers));
    return Collections.unmodifiableList(providers);
  }

  private void addProviderLenient(ServiceLoader.Provider<S> provider, List<S> providers) {
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
