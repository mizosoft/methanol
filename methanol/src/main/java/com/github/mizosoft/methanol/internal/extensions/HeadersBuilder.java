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
    headers.computeIfAbsent(name, __ -> new ArrayList<>()).add(value);
  }

  public void add(String name, List<String> values) {
    if (!values.isEmpty()) {
      headers.computeIfAbsent(name, __ -> new ArrayList<>()).addAll(values);
    }
  }

  public void addAll(Map<String, List<String>> headers) {
    headers.forEach(this::add);
  }

  public void addAll(HttpHeaders headers) {
    addAll(headers.map());
  }

  public void addAll(HeadersBuilder builder) {
    addAll(builder.headers);
  }

  public void set(String name, String value) {
    set(name, List.of(value));
  }

  public void set(String name, List<String> values) {
    if (!values.isEmpty()) {
      headers.put(name, new ArrayList<>(values));
    }
  }

  public void setAll(HttpHeaders headers) {
    headers.map().forEach(this::set);
  }

  public boolean remove(String name) {
    return headers.remove(name) != null;
  }

  public boolean removeIf(BiPredicate<String, String> filter) {
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
