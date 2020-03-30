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

package com.github.mizosoft.methanol.tck;

import static java.nio.charset.StandardCharsets.US_ASCII;

import com.github.mizosoft.methanol.WritableBodyPublisher;
import com.github.mizosoft.methanol.testutils.TestException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow.Publisher;
import org.reactivestreams.tck.TestEnvironment;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.annotations.BeforeClass;

public class WritableBodyPublisherTck extends FlowPublisherVerification<ByteBuffer> {

  private static final int BUFFER_SIZE = 64;
  private static final ByteBuffer DUMMY_ELEMENT = US_ASCII.encode("5".repeat(BUFFER_SIZE)); // whatever

  public WritableBodyPublisherTck() {
    super(new TestEnvironment());
  }

  @BeforeClass
  public static void setUpBufferSize() {
    // ensure written elements match submitted elements
    System.setProperty(
        "com.github.mizosoft.methanol.WritableBodyPublisher.sinkBufferSize",
        Integer.toString(BUFFER_SIZE));
  }

  @Override
  public Publisher<ByteBuffer> createFlowPublisher(long elements) {
    var publisher = WritableBodyPublisher.create();
    try (var channel = publisher.byteChannel()) {
      for (int i = 0; i < elements; i++) {
        int w = channel.write(DUMMY_ELEMENT.duplicate());
        assert w == BUFFER_SIZE;
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
    // Items are buffered in memory before submission so a large # of elements
    // will cause OME when createFlowPublisher() is called (currently happens with
    // required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue) so return
    // an small arbitrary num that allows other tests to pass
    return 1 << 15;
  }
}
