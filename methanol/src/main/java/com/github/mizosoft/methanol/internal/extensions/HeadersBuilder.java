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

package com.github.mizosoft.methanol.internal.extensions;

import static com.github.mizosoft.methanol.internal.Utils.requireValidHeader;
import static com.github.mizosoft.methanol.internal.Utils.requireValidHeaderName;
import static com.github.mizosoft.methanol.internal.Utils.requireValidHeaderValue;
import static java.util.Objects.requireNonNull;

import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiPredicate;

public final class HeadersBuilder {
  private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

  public HeadersBuilder() {}

  public void add(String name, String value) {
    requireValidHeader(name, value);
    addLenient(name, value);
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
    requireValidHeader(name, value);
    var myValues = headers.computeIfAbsent(name, __ -> new ArrayList<>());
    myValues.clear();
    myValues.add(value);
  }

  public void setAll(HttpHeaders headers) {
    clear();
    addAll(headers);
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
    for (var iter = headers.entrySet().iterator(); iter.hasNext(); ) {
      var entry = iter.next();
      var name = entry.getKey();
      var values = entry.getValue();
      mutated |= values.removeIf(value -> filter.test(name, value));
      if (values.isEmpty()) {
        iter.remove();
      }
    }
    return mutated;
  }

  public void clear() {
    headers.clear();
  }

  public HttpHeaders build() {
    return HttpHeaders.of(headers, (n, v) -> true);
  }
}
