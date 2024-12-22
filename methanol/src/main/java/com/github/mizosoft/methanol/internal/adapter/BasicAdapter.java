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

package com.github.mizosoft.methanol.internal.adapter;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.AdapterCodec;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.ResponsePayload;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.concurrent.Delayer;
import com.github.mizosoft.methanol.internal.concurrent.FallbackExecutorProvider;
import com.github.mizosoft.methanol.internal.concurrent.Timeout;
import com.github.mizosoft.methanol.internal.extensions.ByteBufferBodyPublisher;
import com.github.mizosoft.methanol.internal.extensions.Handlers;
import com.github.mizosoft.methanol.internal.extensions.PublisherBodySubscriber;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.net.http.HttpClient.Version;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.Future;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;
import org.checkerframework.checker.nullness.qual.Nullable;

/** An adapter for basic types (e.g., {@code String}, {@code byte[]}). */
public abstract class BasicAdapter extends AbstractBodyAdapter {
  private static final Logger logger = System.getLogger(BasicAdapter.class.getName());

  BasicAdapter() {
    super(MediaType.ANY);
  }

  public static Encoder encoder() {
    return BasicEncoder.INSTANCE;
  }

  public static Decoder decoder() {
    return BasicDecoder.INSTANCE;
  }

  private static final class BasicEncoder extends BasicAdapter implements BaseEncoder {
    static final BasicEncoder INSTANCE = new BasicEncoder();

    private static final Map<TypeRef<?>, BiFunction<?, ? super Charset, ? extends BodyPublisher>>
        ENCODERS;

    static {
      var encoders =
          new LinkedHashMap<TypeRef<?>, BiFunction<?, ? super Charset, ? extends BodyPublisher>>();
      putEncoder(
          encoders,
          CharSequence.class,
          (value, charset) -> BodyPublishers.ofString(value.toString(), charset));
      putEncoder(encoders, InputStream.class, (in, __) -> BodyPublishers.ofInputStream(() -> in));
      putEncoder(encoders, byte[].class, (bytes, __) -> BodyPublishers.ofByteArray(bytes));
      putEncoder(encoders, ByteBuffer.class, (buffer, __) -> new ByteBufferBodyPublisher(buffer));
      putEncoder(encoders, Path.class, (file, __) -> encodeFile(file));
      putEncoder(
          encoders,
          new TypeRef<Supplier<? extends InputStream>>() {},
          (supplier, __) -> BodyPublishers.ofInputStream(supplier));
      putEncoder(
          encoders,
          new TypeRef<Iterable<byte[]>>() {},
          (bytes, __) -> BodyPublishers.ofByteArrays(bytes));
      ENCODERS = Collections.unmodifiableMap(encoders);
    }

    private static <T> void putEncoder(
        Map<TypeRef<?>, BiFunction<?, ? super Charset, ? extends BodyPublisher>> encoders,
        Class<T> type,
        BiFunction<? super T, ? super Charset, ? extends BodyPublisher> encoder) {
      encoders.put(TypeRef.of(type), encoder);
    }

    private static <T> void putEncoder(
        Map<TypeRef<?>, BiFunction<?, ? super Charset, ? extends BodyPublisher>> encoders,
        TypeRef<T> typeRef,
        BiFunction<? super T, ? super Charset, ? extends BodyPublisher> encoder) {
      encoders.put(typeRef, encoder);
    }

    private BasicEncoder() {}

    @Override
    public boolean supportsType(TypeRef<?> typeRef) {
      return encoderOf(typeRef) != null;
    }

    private static BodyPublisher encodeFile(Path path) {
      try {
        return BodyPublishers.ofFile(path);
      } catch (FileNotFoundException e) {
        throw new UncheckedIOException(e);
      }
    }

    @SuppressWarnings("unchecked")
    private static <T> @Nullable BiFunction<T, Charset, BodyPublisher> encoderOf(TypeRef<T> right) {
      for (var entry : ENCODERS.entrySet()) {
        var left = entry.getKey();
        if (left.rawType().isAssignableFrom(right.rawType())) {
          if (left.isRawType()) {
            return (BiFunction<T, Charset, BodyPublisher>) entry.getValue();
          }

          // If left has generics we only accept right if it compares covariantly. Note that this
          // is an ad-hoc comparison that only works for current usage, where encodeable generic
          // supertypes have only one non-generic argument.
          assert left.isParameterizedType();
          if (right
              .resolveSupertype(left.rawType())
              .typeArgumentAt(0)
              .flatMap(
                  rightArg ->
                      left.typeArgumentAt(0)
                          .map(leftArg -> leftArg.rawType().isAssignableFrom(rightArg.rawType())))
              .orElse(false)) {
            return (BiFunction<T, Charset, BodyPublisher>) entry.getValue();
          }
        }
      }
      return null;
    }

    @Override
    public <T> BodyPublisher toBody(T value, TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      return attachMediaType(
          castNonNull(encoderOf(typeRef)).apply(value, hints.mediaTypeOrAny().charsetOrUtf8()),
          hints.mediaTypeOrAny());
    }
  }

  private static final class BasicDecoder extends BasicAdapter implements BaseDecoder {
    static final BasicDecoder INSTANCE = new BasicDecoder();

    private static final Map<TypeRef<?>, Function<? super Hints, ? extends BodySubscriber<?>>>
        DECODERS;

    static {
      var decoders =
          new LinkedHashMap<TypeRef<?>, Function<? super Hints, ? extends BodySubscriber<?>>>();
      putDecoder(
          decoders,
          String.class,
          hints -> BodySubscribers.ofString(hints.mediaTypeOrAny().charsetOrUtf8()));
      putDecoder(decoders, InputStream.class, __ -> BodySubscribers.ofInputStream());
      putDecoder(
          decoders,
          Reader.class,
          hints -> MoreBodySubscribers.ofReader(hints.mediaTypeOrAny().charsetOrUtf8()));
      putDecoder(decoders, byte[].class, __ -> BodySubscribers.ofByteArray());
      putDecoder(
          decoders,
          ByteBuffer.class,
          __ -> BodySubscribers.mapping(BodySubscribers.ofByteArray(), ByteBuffer::wrap));
      putDecoder(
          decoders,
          ResponsePayload.class,
          hints ->
              BodySubscribers.mapping(
                  new PublisherBodySubscriber(),
                  publisher ->
                      new ResponsePayloadImpl(
                          publisher,
                          hints
                              .responseInfo()
                              .orElseThrow(
                                  () ->
                                      new UnsupportedOperationException(
                                          "Expected a ResponseInfo hint")),
                          () ->
                              hints
                                  .get(PayloadHandlerExecutor.class)
                                  .map(PayloadHandlerExecutor::get)
                                  .orElseGet(FallbackExecutorProvider::get),
                          hints.get(AdapterCodec.class).orElseGet(AdapterCodec::installed),
                          hints)));
      putDecoder(
          decoders,
          new TypeRef<>() {},
          hints -> BodySubscribers.ofLines(hints.mediaTypeOrAny().charsetOrUtf8()));
      putDecoder(decoders, new TypeRef<>() {}, __ -> new PublisherBodySubscriber());
      putDecoder(decoders, Void.class, __ -> BodySubscribers.discarding());
      DECODERS = Collections.unmodifiableMap(decoders);
    }

    private static <T> void putDecoder(
        Map<TypeRef<?>, Function<? super Hints, ? extends BodySubscriber<?>>> decoders,
        Class<T> type,
        Function<? super Hints, ? extends BodySubscriber<T>> decoder) {
      decoders.put(TypeRef.of(type), decoder);
    }

    private static <T> void putDecoder(
        Map<TypeRef<?>, Function<? super Hints, ? extends BodySubscriber<?>>> decoders,
        TypeRef<T> typeRef,
        Function<? super Hints, ? extends BodySubscriber<T>> decoder) {
      decoders.put(typeRef, decoder);
    }

    private BasicDecoder() {}

    @Override
    public boolean supportsType(TypeRef<?> typeRef) {
      return DECODERS.containsKey(typeRef);
    }

    @Override
    public <T> BodySubscriber<T> toObject(TypeRef<T> typeRef, Hints hints) {
      requireSupport(typeRef, hints);
      return castNonNull(BasicDecoder.<T>decoderOf(typeRef)).apply(hints);
    }

    @SuppressWarnings("unchecked")
    private static <T> @Nullable Function<? super Hints, ? extends BodySubscriber<T>> decoderOf(
        TypeRef<T> typeRef) {
      return (Function<? super Hints, ? extends BodySubscriber<T>>) DECODERS.get(typeRef);
    }
  }

  private static final class ResponsePayloadImpl implements ResponsePayload {
    private static final Timeout DEFAULT_BODY_DISCARD_TIMEOUT =
        new Timeout(Duration.ofMillis(500), Delayer.systemDelayer());

    private final Publisher<List<ByteBuffer>> publisher;
    private final ResponseInfo responseInfo;
    private final Supplier<Executor> executorSupplier;
    private final AdapterCodec adapterCodec;
    private final Hints hints;
    private boolean closed;

    ResponsePayloadImpl(
        Publisher<List<ByteBuffer>> publisher,
        ResponseInfo responseInfo,
        Supplier<Executor> executorSupplier,
        AdapterCodec adapterCodec,
        Hints hints) {
      this.publisher = requireNonNull(publisher);
      this.responseInfo = requireNonNull(responseInfo);
      this.executorSupplier = requireNonNull(executorSupplier);
      this.adapterCodec = requireNonNull(adapterCodec);
      this.hints = requireNonNull(hints);
    }

    @Override
    public boolean is(MediaType mediaType) {
      return mediaType.includes(
          responseInfo
              .headers()
              .firstValue("Content-Type")
              .map(MediaType::parse)
              .orElse(MediaType.ANY));
    }

    @Override
    public <T> T to(TypeRef<T> typeRef) throws IOException, InterruptedException {
      return Utils.get(
          handleAsync(adapterCodec.handlerOf(typeRef, hints), FlowSupport.SYNC_EXECUTOR));
    }

    @Override
    public <T> T handleWith(BodyHandler<T> bodyHandler) throws IOException, InterruptedException {
      return Utils.get(handleAsync(requireNonNull(bodyHandler), FlowSupport.SYNC_EXECUTOR));
    }

    @Override
    public <T> CompletableFuture<T> toAsync(TypeRef<T> typeRef) {
      return handleAsync(adapterCodec.handlerOf(typeRef, hints), executorSupplier.get());
    }

    @Override
    public <T> CompletableFuture<T> handleWithAsync(BodyHandler<T> bodyHandler) {
      return handleAsync(requireNonNull(bodyHandler), executorSupplier.get());
    }

    private <T> CompletableFuture<T> handleAsync(BodyHandler<T> bodyHandler, Executor executor) {
      requireState(!closed, "Closed");
      closed = true;
      return Handlers.handleAsync(responseInfo, publisher, bodyHandler, executor);
    }

    @Override
    @SuppressWarnings("FutureReturnValueIgnored")
    public void close() {
      boolean wasOpen = !closed;
      closed = true;
      if (wasOpen) {
        BodyHandler<Void> discardingBodyHandler;
        if (responseInfo.version() == Version.HTTP_2) {
          // HTTP2 can discard body more efficiently be sending a RST_STREAM frame.
          discardingBodyHandler = __ -> CancellingBodySubscriber.INSTANCE;
        } else {
          // For HTTP1.1, we better consume the body so the connection returns to the pool. We don't
          // let that take too long however to avoid hanging in background.
          discardingBodyHandler =
              __ ->
                  new CancelOnTimeoutBodySubscriber(
                      hints
                          .get(BodyDiscardTimeoutHint.class)
                          .map(BodyDiscardTimeoutHint::timeout)
                          .orElse(DEFAULT_BODY_DISCARD_TIMEOUT));
        }
        Handlers.handleAsync(responseInfo, publisher, discardingBodyHandler, executorSupplier.get())
            .whenComplete(
                (__, ex) -> {
                  if (ex != null) {
                    logger.log(Level.WARNING, "Exception while discarding response body", ex);
                  }
                });
      }
    }
  }

  private enum CancellingBodySubscriber implements BodySubscriber<Void> {
    INSTANCE;

    @Override
    public CompletionStage<Void> getBody() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      subscription.cancel();
    }

    @Override
    public void onNext(List<ByteBuffer> item) {}

    @Override
    public void onError(Throwable throwable) {
      FlowSupport.onDroppedException(throwable);
    }

    @Override
    public void onComplete() {}
  }

  private static final class CancelOnTimeoutBodySubscriber implements BodySubscriber<Void> {
    private final Upstream upstream = new Upstream();
    private final Future<Void> timeoutFuture;

    CancelOnTimeoutBodySubscriber(Timeout bodyDiscardTimeout) {
      timeoutFuture = bodyDiscardTimeout.onTimeout(upstream::cancel, FlowSupport.SYNC_EXECUTOR);
    }

    @Override
    public CompletionStage<Void> getBody() {
      return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      if (upstream.setOrCancel(subscription)) {
        subscription.request(Long.MAX_VALUE);
      }
    }

    @Override
    public void onNext(List<ByteBuffer> item) {
      requireNonNull(item);
    }

    @Override
    public void onError(Throwable throwable) {
      FlowSupport.onDroppedException(requireNonNull(throwable));
      timeoutFuture.cancel(false);
      upstream.clear();
    }

    @Override
    public void onComplete() {
      timeoutFuture.cancel(false);
      upstream.clear();
    }
  }
}
