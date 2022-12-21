/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.reactivestreams.tck.TestEnvironment;

public class TckUtils {
  private static final long DEFAULT_TIMEOUT_MILLIS = 200L;

  private static final long TIMEOUT_MILLIS =
      getTimeout("TCK_TIMEOUT_MILLIS", DEFAULT_TIMEOUT_MILLIS);
  private static final long NO_SIGNAL_TIMEOUT_MILLIS =
      getTimeout("TCK_NO_SIGNAL_TIMEOUT_MILLIS", TIMEOUT_MILLIS);
  private static final long POLL_TIMEOUT_MILLIS =
      getTimeout("TCK_POLL_TIMEOUT_MILLIS", TIMEOUT_MILLIS);

  private static final int FIXED_POOL_SIZE = 8;

  /**
   * An arbitrary max for the # of elements needed to be precomputed for creating the test
   * publisher. This avoids OMEs when createFlowPublisher() is called with a large # of elements
   * (currently happens with required_spec317_mustNotSignalOnErrorWhenPendingAboveLongMaxValue).
   */
  static final int MAX_PRECOMPUTED_ELEMENTS = 1 << 10;

  private TckUtils() {}

  static ExecutorService fixedThreadPool() {
    return Executors.newFixedThreadPool(FIXED_POOL_SIZE);
  }

  static TestEnvironment testEnvironment() {
    return new TestEnvironment(TIMEOUT_MILLIS, NO_SIGNAL_TIMEOUT_MILLIS, POLL_TIMEOUT_MILLIS);
  }

  static TestEnvironment testEnvironmentWithTimeout(long timeoutMillis) {
    return new TestEnvironment(timeoutMillis, NO_SIGNAL_TIMEOUT_MILLIS, timeoutMillis);
  }

  private static long getTimeout(String prop, long defaultVal) {
    var value = System.getProperty(prop);
    if (value == null) {
      return defaultVal;
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException ex) {
      throw new RuntimeException("unable to parse <" + value + "> for property <" + prop + ">", ex);
    }
  }
}
