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

package com.github.mizosoft.methanol;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.BodyAdapter.Hints;
import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.net.URI;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * An {@code HttpRequest} that can carry arbitrary values, referred to as tags. Tags can be used to
 * carry application-specific data throughout {@link Interceptor interceptors} and listeners. Tags
 * are mapped by their type. One type cannot map to more than one tag.
 *
 * <p>A {@code TaggableRequest} also carries the {@link Hints} to be used when encoding the request
 * body and/or decoding the response body. Hints are like tags but are meant to carry arbitrary
 * values to {@link BodyAdapter adapters} rather than interceptors or listeners.
 */
public abstract class TaggableRequest extends HttpRequest {
  TaggableRequest() {}

  /** Returns the tag associated with the given type if present. */
  public <T> Optional<T> tag(Class<T> type) {
    return tag(TypeRef.of(type));
  }

  /** Returns the tag associated with the given type if present. */
  @SuppressWarnings("unchecked")
  public <T> Optional<T> tag(TypeRef<T> typeRef) {
    return Optional.ofNullable((T) tags().get(requireNonNull(typeRef)));
  }

  /** Returns this request's {@link Hints}. */
  public abstract Hints hints();

  abstract Map<TypeRef<?>, Object> tags();

  static Hints hintsOf(HttpRequest request) {
    return request instanceof TaggableRequest ? ((TaggableRequest) request).hints() : Hints.empty();
  }

  /**
   * Returns either the given request if it's a {@code TaggableRequest} or a new {@code
   * TaggableRequest} copy with no tags otherwise.
   */
  public static TaggableRequest from(HttpRequest request) {
    return request instanceof TaggableRequest
        ? (TaggableRequest) request
        : MutableRequest.copyOf(request).toImmutableRequest();
  }

  /**
   * Returns the tag associated with the given type if the given request is a {@code
   * TaggableRequest} and it has a tag with the given type, otherwise returns an empty {@code
   * Optional}.
   */
  public static <T> Optional<T> tagOf(HttpRequest request, Class<T> type) {
    return request instanceof TaggableRequest
        ? ((TaggableRequest) request).tag(type)
        : Optional.empty();
  }

  /**
   * Returns the tag associated with the given type if the given request is a {@code
   * TaggableRequest} and it has a tag with the given type, otherwise returns an empty {@code
   * Optional}.
   */
  public static <T> Optional<T> tagOf(HttpRequest request, TypeRef<T> typeRef) {
    return request instanceof TaggableRequest
        ? ((TaggableRequest) request).tag(typeRef)
        : Optional.empty();
  }

  /** An {@code HttpRequest.Builder} that allows attaching tags. */
  public interface Builder extends HttpRequest.Builder {

    /** Adds a tag mapped to the given object's runtime type. */
    @CanIgnoreReturnValue
    Builder tag(Object value);

    /** Adds a tag mapped to the given type. */
    @CanIgnoreReturnValue
    <T> Builder tag(Class<T> type, T value);

    /** Adds a tag mapped to the given type. */
    @CanIgnoreReturnValue
    <T> Builder tag(TypeRef<T> typeRef, T value);

    /** Removes the tag associated with the given type. */
    @CanIgnoreReturnValue
    Builder removeTag(Class<?> type);

    /** Removes the tag associated with the given type. */
    @CanIgnoreReturnValue
    Builder removeTag(TypeRef<?> typeRef);

    @Override
    @CanIgnoreReturnValue
    Builder uri(URI uri);

    @Override
    @CanIgnoreReturnValue
    Builder expectContinue(boolean enable);

    @Override
    @CanIgnoreReturnValue
    Builder version(Version version);

    @Override
    @CanIgnoreReturnValue
    Builder header(String name, String value);

    @Override
    @CanIgnoreReturnValue
    Builder headers(String... headers);

    @Override
    @CanIgnoreReturnValue
    Builder timeout(Duration duration);

    @Override
    @CanIgnoreReturnValue
    Builder setHeader(String name, String value);

    @Override
    @CanIgnoreReturnValue
    Builder GET();

    @Override
    @CanIgnoreReturnValue
    Builder POST(BodyPublisher bodyPublisher);

    @Override
    @CanIgnoreReturnValue
    Builder PUT(BodyPublisher bodyPublisher);

    @Override
    @CanIgnoreReturnValue
    Builder DELETE();

    @Override
    @CanIgnoreReturnValue
    Builder method(String method, BodyPublisher bodyPublisher);

    @Override
    Builder copy();

    @Override
    TaggableRequest build();
  }
}
