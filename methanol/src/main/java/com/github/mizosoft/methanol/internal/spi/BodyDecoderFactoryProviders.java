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

package com.github.mizosoft.methanol.internal.spi;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.internal.annotations.DefaultProvider;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/** Utility class for finding decoder factories. */
public class BodyDecoderFactoryProviders {
  private static final ServiceProviders<BodyDecoder.Factory> factories =
      new ServiceProviders<>(BodyDecoder.Factory.class);

  @SuppressWarnings("NonFinalStaticField") // Lazily initialized.
  private static volatile @MonotonicNonNull Map<String, BodyDecoder.Factory> lazyBindings;

  private BodyDecoderFactoryProviders() {}

  public static List<BodyDecoder.Factory> factories() {
    return factories.get();
  }

  public static Map<String, BodyDecoder.Factory> bindings() {
    // Locking is not necessary as the cache itself is locked so the result never changes.
    var bindings = lazyBindings;
    if (bindings == null) {
      bindings = createBindings();
      lazyBindings = bindings;
    }
    return bindings;
  }

  private static Map<String, BodyDecoder.Factory> createBindings() {
    return factories().stream()
        .collect(
            Collectors.toMap(
                BodyDecoder.Factory::encoding,
                Function.identity(),
                (f1, f2) -> {
                  // Allow overriding default providers.
                  if (f1.getClass().isAnnotationPresent(DefaultProvider.class)) {
                    return f2;
                  } else if (f2.getClass().isAnnotationPresent(DefaultProvider.class)) {
                    return f1;
                  } else {
                    return f2;
                  }
                }));
  }
}
