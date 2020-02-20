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

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.BodyDecoder.Factory;
import com.github.mizosoft.methanol.internal.annotations.DefaultProvider;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Utility class for finding decoder factories. */
public class DecoderFactoryFinder {

  private static final ServiceCache<Factory> CACHE = new ServiceCache<>(BodyDecoder.Factory.class);

  private static volatile @MonotonicNonNull Map<String, BodyDecoder.Factory> bindings;

  private DecoderFactoryFinder() {} // non-instantiable

  public static List<BodyDecoder.Factory> findInstalledFactories() {
    return CACHE.getProviders();
  }

  public static Map<String, BodyDecoder.Factory> getInstalledBindings() {
    // Locking is not necessary as CACHE itself is locked so the result never changes
    Map<String, BodyDecoder.Factory> cached = bindings;
    if (cached == null) {
      cached = createBindings();
      bindings = cached;
    }
    return cached;
  }

  private static Map<String, BodyDecoder.Factory> createBindings() {
    Map<String, BodyDecoder.Factory> bindings = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (BodyDecoder.Factory f : findInstalledFactories()) {
      String enc = f.encoding();
      // Only override if default
      bindings.merge(
          enc, f, (f1, f2) -> f1.getClass().isAnnotationPresent(DefaultProvider.class) ? f2 : f1);
    }
    return Collections.unmodifiableMap(bindings);
  }
}
