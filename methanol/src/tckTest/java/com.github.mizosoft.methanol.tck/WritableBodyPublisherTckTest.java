/*
 * Copyright (c) 2025 Moataz Hussein
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
import com.github.mizosoft.methanol.testing.ExecutorContext;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.TestException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.Flow.Publisher;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.ITestResult;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class WritableBodyPublisherTckTest extends FlowPublisherVerification<ByteBuffer> {
  private ExecutorContext executorContext;

  public WritableBodyPublisherTckTest() {
    super(TckUtils.newTestEnvironment());
  }

  @BeforeMethod
  public void setMeUp(ITestResult tr) {
    executorContext = new ExecutorContext(tr.getMethod().getMethodName());
  }

  @AfterMethod
  public void tearMeDown() throws Exception {
    executorContext.close();
  }

  @Override
  public Publisher<ByteBuffer> createFlowPublisher(long elements) {
    var publisher = WritableBodyPublisher.create(TckUtils.BUFFER_SIZE);
    executorContext
        .createExecutor(ExecutorType.CACHED_POOL)
        .execute(
            () -> {
              try (var channel = publisher.byteChannel()) {
                for (int i = 0; i < elements; i++) {
                  var data = TckUtils.generateData();
                  int w = channel.write(data);
                  assert w == data.limit();
                }
              } catch (ClosedChannelException ignored) {
                // Ignore exceptional cases where failures in the flow close the exposed stream.
              } catch (IOException e) {
                // This will be reported when closing the ExecutorContext.
                throw new UncheckedIOException(e);
              }
            });
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
