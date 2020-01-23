/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

package com.github.mizosoft.methanol;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.Objects.requireNonNull;

import java.net.URLEncoder;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.Flow.Subscriber;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@code BodyPublisher} for form submission using the {@code application/x-www-form-urlencoded}
 * request type.
 */
public class FormBodyPublisher implements MimeBodyPublisher {

  private static final MediaType FORM_URL_ENCODED =
      MediaType.of("application", "x-www-form-urlencoded");

  private static final Charset ENCODING_CHARSET = StandardCharsets.UTF_8;

  private final Map<String, List<String>> queries;
  private @MonotonicNonNull String encodedString;

  private FormBodyPublisher(Map<String, List<String>> queries) {
    this.queries = queries;
  }

  /**
   * Returns the url-encoded string of this body's queries.
   */
  public String encodedString() {
    String result = encodedString;
    if (result == null) {
      result = computeEncodedString();
      encodedString = result;
    }
    return result;
  }

  private String computeEncodedString() {
    StringJoiner joiner = new StringJoiner("&");
    for (var entry : queries.entrySet()) {
      String encodedName = URLEncoder.encode(entry.getKey(), ENCODING_CHARSET);
      for (var value : entry.getValue()) {
        joiner.add(encodedName + "=" + URLEncoder.encode(value, ENCODING_CHARSET));
      }
    }
    return joiner.toString();
  }

  /**
   * Returns the first value of the query associated with the given key.
   *
   * @param name the query's name
   */
  public Optional<String> firstQuery(String name) {
    List<String> values = queries.getOrDefault(name, List.of());
    return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
  }

  /**
   * Returns the last value of the query associated with the given key.
   *
   * @param name the query's name
   */
  public Optional<String> lastQuery(String name) {
    List<String> values = queries.getOrDefault(name, List.of());
    return values.isEmpty() ? Optional.empty() : Optional.of(values.get(values.size() - 1));
  }

  /**
   * Returns this body's queries.
   */
  public Map<String, List<String>> queries() {
    return queries;
  }

  @Override
  public MediaType mediaType() {
    return FORM_URL_ENCODED;
  }

  @Override
  public long contentLength() {
    // characters are ascii so 1 byte each
    return encodedString().length();
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
    requireNonNull(subscriber);
    BodyPublishers.ofString(encodedString(), US_ASCII).subscribe(subscriber);
  }

  /**
   * Returns a new {@code FormBodyPublisher.Builder}.
   */
  public static Builder newBuilder() {
    return new Builder();
  }

  /**
   * A builder of {@code FormBodyPublisher} instances.
   */
  public static final class Builder {

    private final Map<String, List<String>> queries;

    Builder() {
      queries = new LinkedHashMap<>();
    }

    /**
     * Adds the query specified by the given name and value.
     *
     * @param name  the query's name
     * @param value the query's value
     */
    public Builder query(String name, String value) {
      queries.computeIfAbsent(name, n -> new ArrayList<>()).add(value);
      return this;
    }

    /**
     * Returns a new {@code FormBodyPublisher} with the added queries.
     */
    public FormBodyPublisher build() {
      if (queries.isEmpty()) {
        return new FormBodyPublisher(Map.of());
      }
      Map<String, List<String>> deepCopy = new LinkedHashMap<>();
      queries.forEach((n, vs) -> deepCopy.put(n, List.copyOf(vs)));
      return new FormBodyPublisher(Collections.unmodifiableMap(deepCopy));
    }
  }
}
