package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.internal.cache.DateUtils.formatHttpDate;

import com.github.mizosoft.methanol.CacheControl;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.internal.cache.CacheResponse.CacheStrategy.StalenessLimit;
import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.cache.Store.Viewer;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import java.io.Closeable;
import java.io.IOException;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Consumer;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A {@code RawResponse} retrieved from cache. */
public final class CacheResponse extends PublisherResponse implements Closeable {
  private final Viewer viewer;
  private final CacheStrategy strategy;

  public CacheResponse(
      CacheResponseMetadata metadata,
      Viewer viewer,
      Executor executor,
      HttpRequest request,
      Instant now) {
    super(metadata.toResponseBuilder().buildTracked(), new CacheReadingPublisher(viewer, executor));
    this.viewer = viewer;
    this.strategy = new CacheStrategy(request, response, now);
  }

  private CacheResponse(
      TrackedResponse<?> response,
      Publisher<List<ByteBuffer>> body,
      Viewer viewer,
      CacheStrategy strategy) {
    super(response, body);
    this.viewer = viewer;
    this.strategy = strategy;
  }

  @Override
  public CacheResponse with(Consumer<ResponseBuilder<?>> mutator) {
    var builder = ResponseBuilder.newBuilder(response);
    mutator.accept(builder);
    return new CacheResponse(builder.buildTracked(), publisher, viewer, strategy);
  }

  @Override
  public void close() {
    viewer.close();
  }

  public @Nullable Editor edit() throws IOException {
    return viewer.edit();
  }

  public boolean isServable() {
    return strategy.canServeCacheResponse(StalenessLimit.MAX_AGE);
  }

  public boolean isServableWhileRevalidating() {
    return strategy.canServeCacheResponse(StalenessLimit.STALE_WHILE_REVALIDATE);
  }

  public boolean isServableOnError() {
    return strategy.canServeCacheResponse(StalenessLimit.STALE_IF_ERROR);
  }

  public HttpRequest toValidationRequest(HttpRequest request) {
    return strategy.toValidationRequest(request);
  }

  /** Add the additional cache headers advised by rfc7234 like Age & Warning. */
  public CacheResponse withCacheHeaders() {
    return with(strategy::addCacheHeaders);
  }

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  static final class CacheStrategy {
    private static final Duration ONE_DAY = Duration.ofDays(1);

    private final CacheControl requestCacheControl;
    private final CacheControl responseCacheControl;
    private final Duration age;
    private final Duration freshness;
    private final Duration staleness;
    private final boolean usesHeuristics;
    private final LocalDateTime effectiveLastModified;
    private final Optional<String> etag;

    CacheStrategy(HttpRequest request, TrackedResponse<?> response, Instant now) {
      this.requestCacheControl = CacheControl.parse(request.headers());
      this.responseCacheControl = CacheControl.parse(response.headers());

      var maxAge = requestCacheControl.maxAge().or(responseCacheControl::maxAge);
      var freshnessPolicy = new FreshnessPolicy(maxAge, response);
      var freshnessLifetime = freshnessPolicy.computeFreshnessLifetime();
      age = freshnessPolicy.computeAge(now);
      freshness = freshnessLifetime.minus(age);
      staleness = freshness.negated();
      usesHeuristics = freshnessPolicy.usesHeuristics();
      effectiveLastModified = freshnessPolicy.effectiveLastModified();
      etag = response.headers().firstValue("ETag");
    }

    boolean canServeCacheResponse(StalenessLimit stalenessLimit) {
      if (requestCacheControl.noCache() || responseCacheControl.noCache()) {
        return false;
      }

      if (!freshness.isNegative()) {
        // The response is fresh, but might not be fresh enough for the client
        return requestCacheControl.minFresh().isEmpty()
            || freshness.compareTo(requestCacheControl.minFresh().get()) >= 0;
      }

      // The server might impose network use for stale responses
      if (responseCacheControl.mustRevalidate()) {
        return false;
      }

      // The response is stale, but might have acceptable staleness
      return stalenessLimit.get(this).filter(limit -> staleness.compareTo(limit) <= 0).isPresent();
    }

    void addCacheHeaders(ResponseBuilder<?> builder) {
      builder.setHeader("Age", Long.toString(age.toSeconds()));
      if (freshness.isNegative()) {
        builder.header("Warning", "110 - \"Response is Stale\"");
      }
      if (usesHeuristics && age.compareTo(ONE_DAY) > 0) {
        builder.header("Warning", "113 - \"Heuristic Expiration\"");
      }
    }

    HttpRequest toValidationRequest(HttpRequest request) {
      return MutableRequest.copyOf(request)
          .setHeader("If-Modified-Since", formatHttpDate(effectiveLastModified))
          .apply(builder -> etag.ifPresent(etag -> builder.setHeader("If-None-Match", etag)));
    }

    enum StalenessLimit {
      MAX_AGE {
        @Override
        Optional<Duration> get(CacheStrategy strategy) {
          // max-stale is only applicable to requests
          var cacheControl = strategy.requestCacheControl;
          return cacheControl.anyMaxStale()
              ? Optional.of(strategy.staleness) // Always accept current staleness
              : cacheControl.maxStale();
        }
      },

      STALE_WHILE_REVALIDATE {
        @Override
        Optional<Duration> get(CacheStrategy strategy) {
          // stale-while-revalidate is only applicable to responses
          return strategy.responseCacheControl.staleWhileRevalidate();
        }
      },

      STALE_IF_ERROR {
        @Override
        Optional<Duration> get(CacheStrategy strategy) {
          // stale-if-error is applicable to requests and responses,
          // but the former overrides the latter.
          return strategy
              .requestCacheControl
              .staleIfError()
              .or(strategy.responseCacheControl::staleIfError);
        }
      };

      abstract Optional<Duration> get(CacheStrategy strategy);
    }
  }
}
