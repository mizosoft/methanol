/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.testing.TestUtils.headers;

import com.github.mizosoft.methanol.MultipartBodyPublisher;
import com.github.mizosoft.methanol.MultipartBodyPublisher.Part;
import com.github.mizosoft.methanol.testing.EmptyPublisher;
import com.github.mizosoft.methanol.testing.ExecutorContext;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.IterablePublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.util.concurrent.Flow.Publisher;
import java.util.stream.Stream;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

@Test
public class MultipartBodyPublisherTckTest extends FlowPublisherVerification<ByteBuffer> {
  private static final int MIN_BATCHES = 2; // Can at least pass a part's heading and last boundary.

  private final ExecutorType executorType;
  
  private ExecutorContext executorContext;

  @Factory(dataProvider = "provider")
  public MultipartBodyPublisherTckTest(ExecutorType executorType) {
    super(TckUtils.newTestEnvironment(MultipartBodyPublisherTckTest.class));
    this.executorType = executorType;
  }

  @BeforeMethod
  public void setMeUp() {
    executorContext = new ExecutorContext();
  }

  @AfterMethod
  public void tearMeDown() throws Exception {
    executorContext.close();
  }

  @Override
  public Publisher<ByteBuffer> createFlowPublisher(long elements) {
    if (elements < MIN_BATCHES) {
      throw new SkipException("Number of items cannot be <= " + elements);
    }

    Publisher<ByteBuffer> partPublisher;
    long remaining = elements - MIN_BATCHES;
    if (remaining > 0) {
      partPublisher =
          new IterablePublisher<>(
              () -> Stream.generate(TckUtils::generateData).limit(remaining).iterator(),
              executorContext.createExecutor(executorType));
    } else {
      partPublisher = EmptyPublisher.instance();
    }
    return MultipartBodyPublisher.newBuilder()
        .part(
            Part.create(
                headers("Content-Type", "text/plain; charset=utf-8"),
                BodyPublishers.fromPublisher(partPublisher)))
        .build();
  }

  @Override
  public Publisher<ByteBuffer> createFailedFlowPublisher() {
    // Must at least submit a part's metadata before failing.
    throw new SkipException("Cannot fail unless at least one item is submitted");
  }

  @DataProvider
  public static Object[][] provider() {
    return new Object[][] {{ExecutorType.CACHED_POOL}, {ExecutorType.SAME_THREAD}};
  }
}
