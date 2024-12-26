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

package com.github.mizosoft.methanol;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.spi.BodyDecoderFactoryProviders;
import java.net.http.HttpResponse.BodySubscriber;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * A {@link BodySubscriber} that decodes the response body for consumption by a downstream
 * subscriber. Despite not implementing the interface, a {@code BodyDecoder} has the same semantics
 * of a {@link java.util.concurrent.Flow.Processor Processor}{@code <List<ByteBuffer>,
 * List<ByteBuffer>>}. It takes lists of {@code ByteBuffers} from the HTTP client, then publishes
 * their decompressed form to the {@link #downstream() downstream subscriber}, which itself converts
 * the former to the desired high-level type.
 *
 * <p>The guarantees given by the HTTP client are also given by the decoder; the downstream receives
 * a strictly ordered representation of the decoded response body in the form of immutable lists of
 * read-only {@code ByteBuffers}, which up on being passed, are no longer referenced by the decoder.
 *
 * <p>Optionally, a {@code BodyDecoder} can have an {@code Executor}. If present, the executor is
 * used to deliver downstream signals. This can lead to overlapped processing between the two
 * subscribers. If an executor is not present, the decoder processes and supplies downstream items
 * in the same thread, which is normally the upstream thread supplying the compressed {@code
 * ByteBuffers}.
 *
 * @param <T> the subscriber's body type
 */
public interface BodyDecoder<T> extends BodySubscriber<T> {

  /**
   * Returns the encoding used by this decoder. This corresponds to the value of the {@code
   * Content-Type} header.
   */
  String encoding();

  /** Returns this decoder's executor if one is specified. */
  Optional<Executor> executor();

  /** Returns this decoder's downstream. */
  BodySubscriber<T> downstream();

  /**
   * Returns the body's {@code CompletionStage}.
   *
   * @implSpec Since this subscriber only acts as a processing stage, the default implementation
   *     returns the body completion stage of the downstream.
   */
  @Override
  default CompletionStage<T> getBody() {
    return downstream().getBody();
  }

  /**
   * A factory of {@link BodyDecoder} instances for some defined encoding. {@code
   * BodyDecoder.Factory} implementations are registered as service-providers by means described in
   * the {@link java.util.ServiceLoader} class.
   */
  interface Factory {

    /** Returns the encoding used by {@code BodyDecoders} created by this factory. */
    String encoding();

    /** Creates a {@code BodyDecoder} with the given downstream. */
    <T> BodyDecoder<T> create(BodySubscriber<T> downstream);

    /** Creates a {@code BodyDecoder} with the given downstream and executor. */
    <T> BodyDecoder<T> create(BodySubscriber<T> downstream, Executor executor);

    /**
     * Returns an immutable list of the registered factories.
     *
     * @throws java.util.ServiceConfigurationError if an error occurs while loading the registered
     *     factories
     */
    static List<Factory> installedFactories() {
      return BodyDecoderFactoryProviders.factories();
    }

    /**
     * Returns an immutable map that case-insensitively maps encodings to their corresponding
     * registered factories. If more than one factory is registered for a given encoding, it is
     * unspecified which one ends up being in the map. However, decoders provided by this module are
     * guaranteed to be overridden by ones registered by other modules for the same encoding.
     */
    static Map<String, Factory> installedBindings() {
      return BodyDecoderFactoryProviders.bindings();
    }

    /** Returns the factory registered for the given encoding, if any. */
    static Optional<Factory> getFactory(String encoding) {
      return Optional.ofNullable(installedBindings().get(requireNonNull(encoding)));
    }
  }
}
