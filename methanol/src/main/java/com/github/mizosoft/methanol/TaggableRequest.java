/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

import com.github.mizosoft.methanol.Methanol.Interceptor;
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
 */
public abstract class TaggableRequest extends HttpRequest {
  TaggableRequest() {}

  /** Returns the tag associated with the given type if present. */
  public <T> Optional<T> tag(Class<T> type) {
    return tag(TypeRef.from(type));
  }

  /** Returns the tag associated with the given type if present. */
  public abstract <T> Optional<T> tag(TypeRef<T> type);

  abstract Map<TypeRef<?>, Object> tags();

  /**
   * Returns either the given request if it's a {@code TaggableRequest} or a new {@code
   * TaggableRequest} copy with no tags otherwise.
   */
  public static TaggableRequest from(HttpRequest request) {
    return request instanceof TaggableRequest
        ? (TaggableRequest) request
        : MutableRequest.copyOf(request).toImmutableRequest();
  }

  /** An {@code HttpRequest.Builder} that allows attaching tags. */
  public interface Builder extends HttpRequest.Builder {

    /** Adds a tag mapped to the given object's runtime type. */
    Builder tag(Object value);

    /** Adds a tag mapped to the given type. */
    <T> Builder tag(Class<T> type, T value);

    /** Adds a tag mapped to the given type. */
    <T> Builder tag(TypeRef<T> type, T value);

    /** Removes the tag associated with the given type. */
    Builder removeTag(Class<?> type);

    /** Removes the tag associated with the given type. */
    Builder removeTag(TypeRef<?> type);

    @Override
    Builder uri(URI uri);

    @Override
    Builder expectContinue(boolean enable);

    @Override
    Builder version(Version version);

    @Override
    Builder header(String name, String value);

    @Override
    Builder headers(String... headers);

    @Override
    Builder timeout(Duration duration);

    @Override
    Builder setHeader(String name, String value);

    @Override
    Builder GET();

    @Override
    Builder POST(BodyPublisher bodyPublisher);

    @Override
    Builder PUT(BodyPublisher bodyPublisher);

    @Override
    Builder DELETE();

    @Override
    Builder method(String method, BodyPublisher bodyPublisher);

    @Override
    Builder copy();

    @Override
    TaggableRequest build();
  }
}
