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
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import java.io.IOException;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Consumer;

/** A {@code RawResponse} that came from the network and may be written to cache. */
public final class NetworkResponse extends PublisherResponse {
  private NetworkResponse(TrackedResponse<?> response, Publisher<List<ByteBuffer>> publisher) {
    super(response, publisher);
  }

  public NetworkResponse writingWith(Editor editor, CacheWritingPublisher.Listener writeListener)
      throws IOException {
    return new NetworkResponse(
        response,
        new CacheWritingPublisher(
            publisher, editor, CacheResponseMetadata.from(response).encode(), writeListener));
  }

  /** Discards the response body in background. */
  public void discard(Executor executor) {
    handleAsync(BodyHandlers.discarding(), executor);
  }

  @Override
  public NetworkResponse with(Consumer<ResponseBuilder<?>> mutator) {
    var builder = ResponseBuilder.newBuilder(response);
    mutator.accept(builder);
    return new NetworkResponse(builder.buildTrackedResponse(), publisher);
  }

  public static NetworkResponse from(TrackedResponse<Publisher<List<ByteBuffer>>> response) {
    return new NetworkResponse(response, response.body());
  }
}
