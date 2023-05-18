/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.tck;

import com.github.mizosoft.methanol.WritableBodyPublisher;
import com.github.mizosoft.methanol.testing.TestException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow.Publisher;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.SkipException;

public class WritableBodyPublisherTckTest extends FlowPublisherVerification<ByteBuffer> {
  public WritableBodyPublisherTckTest() {
    super(TckUtils.testEnvironment());
  }

  @Override
  public Publisher<ByteBuffer> createFlowPublisher(long elements) {
    var publisher = WritableBodyPublisher.create(TckUtils.BUFFER_SIZE);
    try (var channel = publisher.byteChannel()) {
      for (int i = 0; i < elements; i++) {
        var data = TckUtils.generateData();
        int w = channel.write(data);
        assert w == data.limit();
      }
    } catch (IOException e) {
      throw new AssertionError(e);
    }
    return publisher;
  }

  @Override
  public Publisher<ByteBuffer> createFailedFlowPublisher() {
    var publisher = WritableBodyPublisher.create();
    publisher.closeExceptionally(new TestException());
    return publisher;
  }

  @Override
  public long maxElementsFromPublisher() {
    return TckUtils.MAX_PRECOMPUTED_ELEMENTS;
  }

  /**
   * A {@code WritableBodyPublisher} is married to only one subscriber, so it's harmless to keep its
   * reference around until the publisher is dropped by the HTTP client.
   */
  @Override
  public void
      required_spec313_cancelMustMakeThePublisherEventuallyDropAllReferencesToTheSubscriber() {
    throw new SkipException("skipped");
  }
}
