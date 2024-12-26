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

package com.github.mizosoft.methanol.tck;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.flow.AbstractPollableSubscription;
import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.testing.ExecutorContext;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.TestException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.reactivestreams.tck.flow.FlowPublisherVerification;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Factory;
import org.testng.annotations.Test;

@Test
public class AbstractSubscriptionTckTest extends FlowPublisherVerification<Long> {
  private final ExecutorType executorType;

  private ExecutorContext executorContext;

  @Factory(dataProvider = "provider")
  public AbstractSubscriptionTckTest(ExecutorType executorType) {
    super(TckUtils.newTestEnvironment());
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
  public Publisher<Long> createFlowPublisher(long elements) {
    return subscriber ->
        new RangeSubscription(subscriber, executorContext.createExecutor(executorType), 0, elements)
            .fireOrKeepAlive();
  }

  @Override
  public Publisher<Long> createFailedFlowPublisher() {
    return subscriber ->
        new FailedSubscription(subscriber, executorContext.createExecutor(executorType))
            .fireOrKeepAlive();
  }

  @DataProvider
  public static Object[][] provider() {
    return new Object[][] {{ExecutorType.SAME_THREAD}, {ExecutorType.CACHED_POOL}};
  }

  private static final class RangeSubscription extends AbstractPollableSubscription<Long> {
    private long from;
    private final long toExclusive;

    RangeSubscription(
        Subscriber<? super Long> downstream, Executor executor, long from, long toExclusive) {
      super(downstream, executor);
      this.from = from;
      this.toExclusive = toExclusive;
    }

    @Override
    protected @Nullable Long poll() {
      return from < toExclusive ? from++ : null;
    }

    @Override
    protected boolean isComplete() {
      return from >= toExclusive;
    }
  }

  private static final class FailedSubscription extends AbstractSubscription<Long> {
    FailedSubscription(Subscriber<? super Long> downstream, Executor executor) {
      super(requireNonNull(downstream), executor);
    }

    @Override
    protected long emit(Subscriber<? super Long> downstream, long emit) {
      cancelOnError(downstream, new TestException(), true);
      return 0;
    }
  }
}
