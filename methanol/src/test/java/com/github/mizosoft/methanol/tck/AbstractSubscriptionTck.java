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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.flow.AbstractPollableSubscription;
import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.TestException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.reactivestreams.tck.flow.FlowPublisherVerification;

public class AbstractSubscriptionTck extends FlowPublisherVerification<Long> {
  public AbstractSubscriptionTck() {
    super(TckUtils.testEnvironment());
  }

  // Overridden by AsyncAbstractSubscriptionTck for async tests
  Executor executor() {
    return FlowSupport.SYNC_EXECUTOR;
  }

  @Override
  public Publisher<Long> createFlowPublisher(long elements) {
    return d -> new RangeSubscription(d, executor(), 0, elements).fireOrKeepAlive();
  }

  @Override
  public Publisher<Long> createFailedFlowPublisher() {
    return d -> new FailedSubscription(d, executor()).fireOrKeepAlive();
  }

  private static final class RangeSubscription extends AbstractPollableSubscription<Long> {
    private long from;
    private final long toExclusive;

    RangeSubscription(
        Subscriber<? super Long> downstream, Executor executor, long from, long toExclusive) {
      super(requireNonNull(downstream), executor);
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
