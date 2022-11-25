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

package com.github.mizosoft.methanol.internal.cache;

import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.io.IOException;
import java.net.http.HttpResponse.BodyHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** A response with a "raw" body that is yet to be handled. */
public abstract class RawResponse {
  final TrackedResponse<?> response;

  RawResponse(TrackedResponse<?> response) {
    this.response = response;
  }

  public TrackedResponse<?> get() {
    return response;
  }

  public <T> TrackedResponse<T> handle(BodyHandler<T> handler)
      throws IOException, InterruptedException {
    return Utils.get(handleAsync(handler, FlowSupport.SYNC_EXECUTOR));
  }

  public abstract <T> CompletableFuture<TrackedResponse<T>> handleAsync(
      BodyHandler<T> handler, Executor executor);

  public abstract RawResponse with(Consumer<ResponseBuilder<?>> mutator);
}
