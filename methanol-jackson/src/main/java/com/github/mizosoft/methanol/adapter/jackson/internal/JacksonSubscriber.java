/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.adapter.jackson.internal;

import static java.util.Objects.requireNonNull;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.async.ByteArrayFeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.util.TokenBuffer;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.adapter.jackson.ObjectReaderFactory;
import com.github.mizosoft.methanol.internal.flow.Prefetcher;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import java.io.IOException;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscription;

public final class JacksonSubscriber<T> implements BodySubscriber<T> {
  private final ObjectMapper mapper;
  private final ObjectReader objReader;
  private final JsonParser parser;
  private final ByteArrayFeeder feeder;
  private final TokenBuffer tokenBuffer;
  private final CompletableFuture<T> bodyFuture;
  private final Upstream upstream;
  private final Prefetcher prefetcher;

  public JacksonSubscriber(
      ObjectMapper mapper, TypeRef<T> type, ObjectReaderFactory readerFactory, JsonParser parser) {
    this.mapper = mapper;
    this.parser = parser;
    objReader = readerFactory.createReader(mapper, type);
    feeder = (ByteArrayFeeder) parser.getNonBlockingInputFeeder();
    tokenBuffer = new TokenBuffer(parser);
    bodyFuture = new CompletableFuture<>();
    upstream = new Upstream();
    prefetcher = new Prefetcher();
  }

  @Override
  public CompletionStage<T> getBody() {
    return bodyFuture;
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (upstream.setOrCancel(subscription)) {
      prefetcher.initialize(upstream);
    }
  }

  @Override
  public void onNext(List<ByteBuffer> item) {
    requireNonNull(item);
    byte[] bytes = JacksonAdapterUtils.collectBytes(item);
    try {
      feeder.feedInput(bytes, 0, bytes.length);
      flushParser();
    } catch (Throwable t) {
      bodyFuture.completeExceptionally(t);
      upstream.cancel();
      return;
    }
    prefetcher.update(upstream);
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    upstream.clear();
    bodyFuture.completeExceptionally(throwable);
  }

  @Override
  public void onComplete() {
    upstream.clear();
    feeder.endOfInput();
    try {
      flushParser();
      bodyFuture.complete(objReader.readValue(tokenBuffer.asParser(mapper)));
    } catch (Throwable t) {
      bodyFuture.completeExceptionally(t);
    }
  }

  private void flushParser() throws IOException {
    JsonToken token;
    while ((token = parser.nextToken()) != null && token != JsonToken.NOT_AVAILABLE) {
      tokenBuffer.copyCurrentEvent(parser);
    }
  }
}
