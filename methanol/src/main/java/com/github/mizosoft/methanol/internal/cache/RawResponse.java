package com.github.mizosoft.methanol.internal.cache;

import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.io.IOException;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;

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
    try {
      return handleAsync(handler, FlowSupport.SYNC_EXECUTOR).get();
    } catch (ExecutionException e) {
      var cause = e.getCause();
      var rethrownCause = tryGetRethrownIOCause(cause);
      if (rethrownCause instanceof RuntimeException) {
        throw (RuntimeException) rethrownCause;
      } else if (rethrownCause instanceof Error) {
        throw (Error) rethrownCause;
      } else if (rethrownCause instanceof IOException) {
        throw (IOException) rethrownCause;
      } else if (rethrownCause instanceof InterruptedException) {
        throw (InterruptedException) rethrownCause;
      } else {
        throw new IOException(cause.getMessage(), cause);
      }
    }
  }

  public abstract <T> CompletableFuture<TrackedResponse<T>> handleAsync(
      BodyHandler<T> handler, Executor executor);

  public abstract RawResponse with(Consumer<ResponseBuilder<?>> mutator);

  public static RawResponse from(TrackedResponse<Publisher<List<ByteBuffer>>> response) {
    return new PublisherResponse(response, response.body());
  }

  private static @Nullable Throwable tryGetRethrownIOCause(Throwable cause) {
    var message = cause.getMessage();
    var causeType = cause.getClass();

    // Try Throwable(String, Throwable)
    try {
      return causeType.getConstructor(String.class, Throwable.class).newInstance(message, cause);
    } catch (ReflectiveOperationException ignored) {
    }

    // Try Throwable(String).initCause(Throwable)
    try {
      return causeType.getConstructor(String.class).newInstance(message).initCause(cause);
    } catch (ReflectiveOperationException ignored) {
    }

    // Try Throwable(Throwable)
    try {
      return causeType.getConstructor(Throwable.class).newInstance(cause);
    } catch (ReflectiveOperationException ignored) {
    }

    // Try no arg constructor
    try {
      return causeType.getConstructor().newInstance();
    } catch (ReflectiveOperationException ignored) {
      return null;
    }
  }
}
