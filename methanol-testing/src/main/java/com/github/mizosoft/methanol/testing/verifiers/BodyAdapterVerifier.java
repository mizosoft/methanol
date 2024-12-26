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

package com.github.mizosoft.methanol.testing.verifiers;

import static java.util.function.Predicate.not;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.TypeRef;

/** A small DSL for testing {@link BodyAdapter} implementations. */
public abstract class BodyAdapterVerifier<
    A extends BodyAdapter, VERIFIER extends BodyAdapterVerifier<A, VERIFIER>> {
  final A adapter;

  BodyAdapterVerifier(A adapter) {
    this.adapter = adapter;
  }

  abstract VERIFIER self();

  public VERIFIER isCompatibleWith(MediaType mediaType) {
    assertThat(mediaType).matches(adapter::isCompatibleWith, "is compatible");
    return self();
  }

  public VERIFIER isCompatibleWith(String mediaType) {
    return isCompatibleWith(MediaType.parse(mediaType));
  }

  public VERIFIER isNotCompatibleWith(MediaType mediaType) {
    assertThat(mediaType).matches(not(adapter::isCompatibleWith), "is not compatible");
    return self();
  }

  public VERIFIER isNotCompatibleWith(String mediaType) {
    return isNotCompatibleWith(MediaType.parse(mediaType));
  }

  public VERIFIER supports(Class<?> type) {
    return supports(TypeRef.of(type));
  }

  public VERIFIER supports(TypeRef<?> type) {
    assertThat(type).matches(adapter::supportsType);
    return self();
  }

  public VERIFIER doesNotSupport(Class<?> type) {
    return doesNotSupport(TypeRef.of(type));
  }

  public VERIFIER doesNotSupport(TypeRef<?> type) {
    assertThat(type).matches(not(adapter::supportsType));
    return self();
  }
}
