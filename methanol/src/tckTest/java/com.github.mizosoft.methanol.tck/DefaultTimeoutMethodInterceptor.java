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

import java.util.List;
import org.testng.IMethodInstance;
import org.testng.IMethodInterceptor;
import org.testng.ITestContext;

public final class DefaultTimeoutMethodInterceptor implements IMethodInterceptor {
  private static final int DEFAULT_TIMEOUT_MILLIS = 5000;
  private static final int DEFAULT_SLOW_TIMEOUT_MILLIS = 100000;

  public DefaultTimeoutMethodInterceptor() {}
  
  @Override
  public List<IMethodInstance> intercept(List<IMethodInstance> methods, ITestContext context) {
    for (var method : methods) {
      if (method.getMethod().getTimeOut() == 0) { // Don't override timeout.
        int timeoutMillis =
            method.getInstance().getClass().isAnnotationPresent(Slow.class)
                ? DEFAULT_SLOW_TIMEOUT_MILLIS
                : DEFAULT_TIMEOUT_MILLIS;
        method.getMethod().setTimeOut(timeoutMillis);
      }
    }
    return methods;
  }
}
