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

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static java.net.HttpURLConnection.HTTP_NOT_MODIFIED;
import static java.util.Objects.requireNonNullElseGet;

import com.github.mizosoft.methanol.HttpStatus;
import com.github.mizosoft.methanol.Methanol.Interceptor;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.extensions.Handlers;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.function.Unchecked;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An {@link Interceptor} that follows redirects. The interceptor is applied prior to the cache
 * interceptor only if one is installed. Allowing the cache to intercept redirects increases its
 * efficiency as network access can be avoided in case a redirected URI is accessed repeatedly
 * (provided the redirecting response is cacheable). Additionally, this ensures correctness in case
 * a cacheable response is received for a redirected request. In such case, the response should be
 * cached for the URI the request was redirected to, not the initiating URI.
 *
 * <p>For best compatibility, the interceptor follows HttpClient's redirecting behaviour.
 */
public final class RedirectingInterceptor implements Interceptor {
  private static final int DEFAULT_MAX_REDIRECTS = 5;
  private static final int MAX_REDIRECTS =
      Integer.getInteger("jdk.httpclient.redirects.retrylimit", DEFAULT_MAX_REDIRECTS);

  private final Redirect policy;

  /** The executor used for invoking the response handler. */
  private final Executor handlerExecutor;

  public RedirectingInterceptor(Redirect policy, @Nullable Executor handlerExecutor) {
    this.policy = policy;
    this.handlerExecutor =
        requireNonNullElseGet(
            handlerExecutor,
            () ->
                Executors.newCachedThreadPool(
                    runnable -> {
                      var thread = new Thread(runnable);
                      thread.setDaemon(true);
                      return thread;
                    }));
  }

  @Override
  public <T> HttpResponse<T> intercept(HttpRequest request, Chain<T> chain)
      throws IOException, InterruptedException {
    return policy == Redirect.NEVER
        ? chain.forward(request)
        : Utils.block(doIntercept(request, chain, false));
  }

  @Override
  public <T> CompletableFuture<HttpResponse<T>> interceptAsync(
      HttpRequest request, Chain<T> chain) {
    return policy == Redirect.NEVER
        ? chain.forwardAsync(request)
        : doIntercept(request, chain, true);
  }

  private <T> CompletableFuture<HttpResponse<T>> doIntercept(
      HttpRequest request, Chain<T> chain, boolean async) {
    return new Redirector(
            request, new SendAdapter(Handlers.toPublisherChain(chain, handlerExecutor), async))
        .sendAndFollowUp()
        .thenApply(Redirector::result)
        .thenCompose(
            response -> Handlers.handleAsync(response, chain.bodyHandler(), handlerExecutor));
  }

  private static final class SendAdapter {
    private final Chain<Publisher<List<ByteBuffer>>> chain;
    private final boolean async;

    SendAdapter(Chain<Publisher<List<ByteBuffer>>> chain, boolean async) {
      this.chain = chain;
      this.async = async;
    }

    CompletableFuture<HttpResponse<Publisher<List<ByteBuffer>>>> send(HttpRequest request) {
      return async
          ? chain.forwardAsync(request)
          : Unchecked.supplyAsync(() -> chain.forward(request), FlowSupport.SYNC_EXECUTOR);
    }
  }

  private final class Redirector {
    private final HttpRequest request;
    private final SendAdapter sendAdapter;
    private final AtomicInteger redirectCount;
    private final @Nullable HttpResponse<Publisher<List<ByteBuffer>>> response;
    private final @Nullable HttpResponse<Publisher<List<ByteBuffer>>> previousResponse;

    Redirector(HttpRequest request, SendAdapter sendAdapter) {
      this(request, sendAdapter, new AtomicInteger(), null, null);
    }

    private Redirector(
        HttpRequest request,
        SendAdapter sendAdapter,
        AtomicInteger redirectCount,
        @Nullable HttpResponse<Publisher<List<ByteBuffer>>> response,
        @Nullable HttpResponse<Publisher<List<ByteBuffer>>> previousResponse) {
      this.request = request;
      this.sendAdapter = sendAdapter;
      this.redirectCount = redirectCount;
      this.response = response;
      this.previousResponse = previousResponse;
    }

    HttpResponse<Publisher<List<ByteBuffer>>> result() {
      requireState(response != null, "absent response");
      return castNonNull(response);
    }

    private Redirector withResponse(HttpResponse<Publisher<List<ByteBuffer>>> response) {
      var newResponse = response;
      if (previousResponse != null) {
        var previousResponseWithoutBody =
            ResponseBuilder.newBuilder(previousResponse).dropBody().build();
        newResponse =
            ResponseBuilder.newBuilder(response)
                .previousResponse(previousResponseWithoutBody)
                .build();
      }
      return new Redirector(request, sendAdapter, redirectCount, newResponse, null);
    }

    CompletableFuture<Redirector> sendAndFollowUp() {
      return sendAdapter
          .send(request)
          .thenApply(this::withResponse)
          .thenCompose(Redirector::followUp);
    }

    CompletableFuture<Redirector> followUp() {
      var response = result();
      var redirectedRequest = redirectedRequest(response);
      if (redirectedRequest == null || redirectCount.incrementAndGet() > MAX_REDIRECTS) {
        // Reached destination or exceeded allowed redirects
        return CompletableFuture.completedFuture(this);
      }

      // Discard the body of the redirecting response
      Handlers.handleAsync(response, BodyHandlers.discarding(), handlerExecutor);

      // Follow redirected request
      return new Redirector(redirectedRequest, sendAdapter, redirectCount, null, response)
          .sendAndFollowUp();
    }

    public @Nullable HttpRequest redirectedRequest(HttpResponse<?> response) {
      if (policy == Redirect.NEVER) {
        return null;
      }

      int statusCode = response.statusCode();
      if (isRedirecting(statusCode) && statusCode != HTTP_NOT_MODIFIED) {
        var redirectedUri = redirectedUri(response.headers());
        var newMethod = redirectedMethod(response.statusCode());
        if (canRedirectTo(redirectedUri)) {
          return createRedirectedRequest(redirectedUri, statusCode, newMethod);
        }
      }
      return null;
    }

    private URI redirectedUri(HttpHeaders responseHeaders) {
      return responseHeaders
          .firstValue("Location")
          .map(request.uri()::resolve)
          .orElseThrow(() -> new UncheckedIOException(new IOException("invalid redirection")));
    }

    // jdk.internal.net.http.RedirectFilter.redirectedMethod
    private String redirectedMethod(int statusCode) {
      var originalMethod = request.method();
      switch (statusCode) {
        case 301:
        case 302:
          return originalMethod.equals("POST") ? "GET" : originalMethod;
        case 303:
          return "GET";
        case 307:
        case 308:
        default:
          return originalMethod;
      }
    }

    // jdk.internal.net.http.RedirectFilter.canRedirect
    private boolean canRedirectTo(URI redirectedUri) {
      var oldScheme = request.uri().getScheme();
      var newScheme = redirectedUri.getScheme();
      switch (policy) {
        case ALWAYS:
          return true;
        case NEVER:
          return false;
        case NORMAL:
          return newScheme.equalsIgnoreCase(oldScheme) || newScheme.equalsIgnoreCase("https");
        default:
          throw new AssertionError("unexpected policy: " + policy);
      }
    }

    private HttpRequest createRedirectedRequest(
        URI redirectedUri, int statusCode, String newMethod) {
      boolean retainBody = statusCode != 303 && request.method().equals(newMethod);
      var newBody =
          request.bodyPublisher().filter(__ -> retainBody).orElseGet(BodyPublishers::noBody);
      return MutableRequest.copyOf(request).uri(redirectedUri).method(newMethod, newBody);
    }

    // jdk.internal.net.http.RedirectFilter.isRedirecting
    private boolean isRedirecting(int statusCode) {
      // 309-399 Unassigned => don't follow
      if (!HttpStatus.isRedirection(statusCode) || statusCode > 308) {
        return false;
      }

      switch (statusCode) {
          // 300: MultipleChoice => don't follow
          // 304: Not Modified => don't follow
          // 305: Proxy Redirect => don't follow.
          // 306: Unused => don't follow
        case 300:
        case 304:
        case 305:
        case 306:
          return false;
          // 301, 302, 303, 307, 308: OK to follow.
        default:
          return true;
      }
    }
  }
}
