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

package com.github.mizosoft.methanol.testing;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.Flow.Publisher;
import java.util.stream.Collectors;

/** Collects a stream of {@code ByteBuffers}. */
public final class ByteBufferCollector {
  private ByteBufferCollector() {}

  public static ByteBuffer collect(Collection<ByteBuffer> buffers) {
    var collected = ByteBuffer.allocate(buffers.stream().mapToInt(ByteBuffer::remaining).sum());
    buffers.forEach(collected::put);
    return collected.flip();
  }

  public static ByteBuffer collect(Publisher<ByteBuffer> publisher) {
    var subscriber = new TestSubscriber<ByteBuffer>();
    publisher.subscribe(subscriber);
    return collect(subscriber.pollAll());
  }

  public static ByteBuffer collectMulti(Collection<List<ByteBuffer>> buffers) {
    return collect(
        buffers.stream().flatMap(Collection::stream).collect(Collectors.toUnmodifiableList()));
  }

  public static ByteBuffer collectMulti(Publisher<List<ByteBuffer>> publisher) {
    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    publisher.subscribe(subscriber);
    return collectMulti(subscriber.pollAll());
  }
}
