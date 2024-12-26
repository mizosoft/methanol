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

package com.github.mizosoft.methanol.internal.extensions;

import static com.github.mizosoft.methanol.internal.Utils.requireValidHeader;
import static com.github.mizosoft.methanol.internal.Utils.requireValidHeaderName;
import static com.github.mizosoft.methanol.internal.Utils.requireValidHeaderValue;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.HeadersAccumulator;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public final class HeadersBuilder {
  private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
  private @MonotonicNonNull HeadersAccumulatorView lazyAccumulatorView;

  public HeadersBuilder() {}

  public void add(String name, String value) {
    requireValidHeader(name, value);
    addLenient(name, value);
  }

  public void addAll(String... headers) {
    requireArgument(
        headers.length > 0 && headers.length % 2 == 0,
        "Expected a even-numbered, positive array length: %d",
        headers.length);
    for (int i = 0; i < headers.length; i += 2) {
      add(headers[i], headers[i + 1]);
    }
  }

  public void addAll(HttpHeaders headers) {
    headers
        .map()
        .forEach(
            (name, values) -> {
              var myValues =
                  this.headers.computeIfAbsent(
                      requireValidHeaderName(name), __ -> new ArrayList<>());
              values.forEach(value -> myValues.add(requireValidHeaderValue(value)));
            });
  }

  public void addAll(HeadersBuilder builder) {
    addAllLenient(builder.headers);
  }

  public void addLenient(String name, String value) {
    headers
        .computeIfAbsent(requireNonNull(name), __ -> new ArrayList<>())
        .add(requireNonNull(value));
  }

  public void addAllLenient(HttpHeaders headers) {
    addAllLenient(headers.map());
  }

  /** Adds all given headers. Assumes keys & values can't be null. */
  private void addAllLenient(Map<String, List<String>> headers) {
    headers.forEach(
        (name, values) ->
            this.headers.computeIfAbsent(name, __ -> new ArrayList<>()).addAll(values));
  }

  public void set(String name, String value) {
    set(name, List.of(value));
  }

  public void set(String name, List<String> values) {
    requireValidHeaderName(name);
    var myValues = headers.computeIfAbsent(name, __ -> new ArrayList<>());
    myValues.clear();
    values.forEach(value -> myValues.add(requireValidHeaderValue(value)));
  }

  public void setIfAbsent(String name, String value) {
    setIfAbsent(name, List.of(value));
  }

  public void setIfAbsent(String name, List<String> values) {
    requireValidHeaderName(name);
    headers.computeIfAbsent(
        name,
        __ -> {
          var myValues = new ArrayList<String>();
          values.forEach(value -> myValues.add(requireValidHeaderValue(value)));
          return myValues;
        });
  }

  public void setLenient(String name, List<String> values) {
    var myValues = headers.computeIfAbsent(name, __ -> new ArrayList<>());
    myValues.clear();
    myValues.addAll(values);
  }

  public boolean remove(String name) {
    return headers.remove(requireNonNull(name)) != null;
  }

  public boolean removeIf(BiPredicate<String, String> filter) {
    requireNonNull(filter);
    boolean mutated = false;
    for (var iterator = headers.entrySet().iterator(); iterator.hasNext(); ) {
      var entry = iterator.next();
      var name = entry.getKey();
      var values = entry.getValue();
      mutated |= values.removeIf(value -> filter.test(name, value));
      if (values.isEmpty()) {
        iterator.remove();
      }
    }
    return mutated;
  }

  public void clear() {
    headers.clear();
  }

  public Optional<String> lastValue(String name) {
    var values = headers.get(name);
    return values != null ? Optional.of(values.get(values.size() - 1)) : Optional.empty();
  }

  public HttpHeaders build() {
    return HttpHeaders.of(headers, (n, v) -> true);
  }

  public HeadersAccumulator<?> asHeadersAccumulator() {
    return lazyAccumulatorView != null
        ? lazyAccumulatorView
        : (lazyAccumulatorView = new HeadersAccumulatorView());
  }

  private final class HeadersAccumulatorView implements HeadersAccumulator<HeadersAccumulatorView> {
    @Override
    public HeadersAccumulatorView header(String name, String value) {
      add(name, value);
      return this;
    }

    @Override
    public HeadersAccumulatorView headers(String... headers) {
      addAll(headers);
      return this;
    }

    @Override
    public HeadersAccumulatorView headers(HttpHeaders headers) {
      addAll(headers);
      return this;
    }

    @Override
    public HeadersAccumulatorView setHeader(String name, String value) {
      set(name, value);
      return this;
    }

    @Override
    public HeadersAccumulatorView setHeader(String name, List<String> values) {
      set(name, values);
      return this;
    }

    @Override
    public HeadersAccumulatorView setHeaderIfAbsent(String name, String value) {
      setIfAbsent(name, value);
      return this;
    }

    @Override
    public HeadersAccumulatorView setHeaderIfAbsent(String name, List<String> values) {
      setIfAbsent(name, values);
      return this;
    }

    @Override
    public HeadersAccumulatorView removeHeaders() {
      clear();
      return this;
    }

    @Override
    public HeadersAccumulatorView removeHeader(String name) {
      remove(name);
      return this;
    }

    @Override
    public HeadersAccumulatorView removeHeadersIf(BiPredicate<String, String> filter) {
      removeIf(filter);
      return this;
    }
  }
}
