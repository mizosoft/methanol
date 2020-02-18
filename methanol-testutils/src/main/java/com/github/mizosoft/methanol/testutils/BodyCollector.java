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

package com.github.mizosoft.methanol.testutils;

import static java.util.Objects.requireNonNull;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;

/** Collects BodyPublisher's content. */
public class BodyCollector implements Flow.Subscriber<ByteBuffer> {

  private final CompletableFuture<ByteBuffer> bodyCF;
  private final List<ByteBuffer> buffers;

  public BodyCollector() {
    bodyCF = new CompletableFuture<>();
    buffers = new ArrayList<>();
  }

  @Override
  public void onSubscribe(Flow.Subscription subscription) {
    subscription.request(Long.MAX_VALUE);
  }

  @Override
  public void onNext(ByteBuffer item) {
    requireNonNull(item);
    buffers.add(item);
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    bodyCF.completeExceptionally(throwable);
  }

  @Override
  public void onComplete() {
    bodyCF.complete(collect(buffers));
  }

  public CompletableFuture<ByteBuffer> body() {
    return bodyCF;
  }

  public static ByteBuffer collect(List<ByteBuffer> buffers) {
    ByteBuffer compacted =
        ByteBuffer.allocate(buffers.stream().mapToInt(ByteBuffer::remaining).sum());
    buffers.forEach(compacted::put);
    return compacted.flip();
  }

  public static ByteBuffer collect(Flow.Publisher<ByteBuffer> publisher) {
    var collector = new BodyCollector();
    publisher.subscribe(collector);
    return collector.bodyCF.join();
  }
}
