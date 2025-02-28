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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static com.github.mizosoft.methanol.internal.text.HttpCharMatchers.BOUNDARY_MATCHER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.flow.AbstractPollableSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.Prefetcher;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@code BodyPublisher} for <a
 * href="https://tools.ietf.org/html/rfc2046#section-5.1">multipart</a> bodies.
 */
@SuppressWarnings("ReferenceEquality") // ByteBuffer sentinel values.
public final class MultipartBodyPublisher implements MimeBodyPublisher {
  private static final Logger logger = System.getLogger(MultipartBodyPublisher.class.getName());

  private static final long UNKNOWN_LENGTH = -1;
  private static final long UNINITIALIZED_LENGTH = -2;

  private static final String BOUNDARY_ATTRIBUTE = "boundary";

  private final List<Part> parts;
  private final MediaType mediaType;
  private final String boundary;
  private long lazyContentLength = UNINITIALIZED_LENGTH;

  private MultipartBodyPublisher(List<Part> parts, MediaType mediaType) {
    this.parts = requireNonNull(parts);
    this.mediaType = requireNonNull(mediaType);

    var boundary = mediaType.parameters().get(BOUNDARY_ATTRIBUTE);
    requireArgument(boundary != null, "Missing boundary");
    this.boundary = castNonNull(boundary);
  }

  /** Returns the boundary of this multipart body. */
  public String boundary() {
    return boundary;
  }

  /** Returns an immutable list containing this body's parts. */
  public List<Part> parts() {
    return parts;
  }

  @Override
  public MediaType mediaType() {
    return mediaType;
  }

  @Override
  public long contentLength() {
    long contentLength = lazyContentLength;
    if (contentLength == UNINITIALIZED_LENGTH) {
      contentLength = computeContentLength();
      lazyContentLength = contentLength;
    }
    return contentLength;
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
    new MultipartSubscription(boundary, parts, subscriber).fireOrKeepAlive();
  }

  private long computeContentLength() {
    long rawContentLength = 0L; // Content length of "raw" parts without metadata.
    var metadata = new StringBuilder();
    for (int i = 0; i < parts.size(); i++) {
      var part = parts.get(i);
      long partContentLength = part.bodyPublisher().contentLength();
      if (partContentLength < 0) {
        return UNKNOWN_LENGTH;
      }
      rawContentLength += partContentLength;

      BoundaryAppender.get(i, parts.size()).append(metadata, boundary);
      appendPartHeaders(metadata, part);
      metadata.append("\r\n");
    }
    BoundaryAppender.LAST.append(metadata, boundary);
    return rawContentLength + UTF_8.encode(CharBuffer.wrap(metadata)).remaining();
  }

  private static void appendPartHeaders(StringBuilder target, Part part) {
    part.headers()
        .map()
        .forEach((name, values) -> values.forEach(value -> appendHeader(target, name, value)));
    var publisher = part.bodyPublisher();
    if (publisher instanceof MimeBodyPublisher) {
      appendHeader(target, "Content-Type", ((MimeBodyPublisher) publisher).mediaType().toString());
    }
  }

  private static void appendHeader(StringBuilder target, String name, String value) {
    target.append(name).append(": ").append(value).append("\r\n");
  }

  /** Returns a new {@code MultipartBodyPublisher.Builder}. */
  public static Builder newBuilder() {
    return new Builder();
  }

  /** A part in a multipart request body. */
  public static final class Part {
    private final HttpHeaders headers;
    private final BodyPublisher bodyPublisher;

    Part(HttpHeaders headers, BodyPublisher bodyPublisher) {
      this.headers = requireNonNull(headers);
      this.bodyPublisher = requireNonNull(bodyPublisher);
      validateHeaderNames(headers.map().keySet(), bodyPublisher);
    }

    /** Returns this part's headers. */
    public HttpHeaders headers() {
      return headers;
    }

    /** Returns the {@code BodyPublisher} of this part's content. */
    public BodyPublisher bodyPublisher() {
      return bodyPublisher;
    }

    /**
     * Returns a new {@code Part} with the given headers and body publisher.
     *
     * @throws IllegalArgumentException if any of the given headers' names is invalid or if a {@code
     *     Content-Type} header is present but the given publisher is a {@link MimeBodyPublisher}
     */
    public static Part create(HttpHeaders headers, BodyPublisher bodyPublisher) {
      return new Part(headers, bodyPublisher);
    }

    private static void validateHeaderNames(Set<String> names, BodyPublisher publisher) {
      requireArgument(
          !(names.contains("Content-Type") && publisher instanceof MimeBodyPublisher),
          "unexpected Content-Type header");
      names.forEach(Utils::requireValidHeaderName);
    }
  }

  /**
   * A builder of {@code MultipartBodyPublisher} instances. The default multipart subtype used by
   * the builder is {@code form-data}.
   *
   * <p>Any part added with a {@link MimeBodyPublisher} will have it's {@code Content-Type} header
   * automatically appended to the part's headers during transmission.
   */
  public static final class Builder {
    private static final int MAX_BOUNDARY_LENGTH = 70;

    private static final MediaType DEFAULT_MULTIPART_MEDIA_TYPE =
        MediaType.of("multipart", "form-data");

    private final List<Part> parts = new ArrayList<>();
    private MediaType mediaType = DEFAULT_MULTIPART_MEDIA_TYPE;

    /**
     * Sets the boundary of the multipart body.
     *
     * @throws IllegalArgumentException if the boundary is invalid
     */
    @CanIgnoreReturnValue
    public Builder boundary(String boundary) {
      mediaType = mediaType.withParameter(BOUNDARY_ATTRIBUTE, requireValidBoundary(boundary));
      return this;
    }

    /**
     * Sets the media type of the multipart body. If the given media type has a boundary parameter,
     * it will be used as the body's boundary.
     *
     * @throws IllegalArgumentException if the given media type is not a multipart type or if it has
     *     an invalid boundary parameter
     */
    @CanIgnoreReturnValue
    public Builder mediaType(MediaType mediaType) {
      this.mediaType = requireValidMediaType(mediaType);
      return this;
    }

    /** Adds the given part. */
    @CanIgnoreReturnValue
    public Builder part(Part part) {
      parts.add(requireNonNull(part));
      return this;
    }

    /** Adds a form field with the given name and body. */
    @CanIgnoreReturnValue
    public Builder formPart(String name, BodyPublisher bodyPublisher) {
      return part(Part.create(getFormHeaders(name, null), bodyPublisher));
    }

    /** Adds a form field with the given name, filename and body. */
    @CanIgnoreReturnValue
    public Builder formPart(String name, String filename, BodyPublisher bodyPublisher) {
      return part(Part.create(getFormHeaders(name, filename), bodyPublisher));
    }

    /** Adds a form field with the given name, filename, body and media type. */
    @CanIgnoreReturnValue
    public Builder formPart(
        String name, String filename, BodyPublisher bodyPublisher, MediaType mediaType) {
      return formPart(name, filename, MoreBodyPublishers.ofMediaType(bodyPublisher, mediaType));
    }

    /**
     * Adds a {@code text/plain} form field with the given name and value. {@code UTF-8} is used for
     * encoding the field's body.
     */
    @CanIgnoreReturnValue
    public Builder textPart(String name, Object value) {
      return textPart(name, value, UTF_8);
    }

    /**
     * Adds a {@code text/plain} form field with the given name and value, using the given charset
     * for encoding the field's value.
     */
    @CanIgnoreReturnValue
    public Builder textPart(String name, Object value, Charset charset) {
      return formPart(name, BodyPublishers.ofString(value.toString(), charset));
    }

    /**
     * Adds a file form field with the given name and file. The field's filename property will be
     * that of the given path's {@link Path#getFileName() filename component}. The given path will
     * be used to {@link Files#probeContentType(Path) probe} the part's media type. If probing
     * fails, either by throwing an exception or returning {@code null}, {@code
     * application/octet-stream} will be used.
     *
     * @throws FileNotFoundException if a file with the given path cannot be found
     */
    @CanIgnoreReturnValue
    public Builder filePart(String name, Path file) throws FileNotFoundException {
      return filePart(name, file, probeMediaType(file));
    }

    /**
     * Adds a file form field with given name, file and media type. The field's filename property
     * will be that of the given path's {@link Path#getFileName() filename compontent}.
     *
     * @throws FileNotFoundException if a file with the given path cannot be found
     */
    @CanIgnoreReturnValue
    public Builder filePart(String name, Path file, MediaType mediaType)
        throws FileNotFoundException {
      var filenameComponent = file.getFileName();
      var filenameString = filenameComponent != null ? filenameComponent.toString() : "";
      var publisher = MoreBodyPublishers.ofMediaType(BodyPublishers.ofFile(file), mediaType);
      return formPart(name, filenameString, publisher);
    }

    /**
     * Returns a new {@code MultipartBodyPublisher}. If no boundary has been set, a randomly
     * generated one is used.
     *
     * @throws IllegalStateException if no part was added
     */
    public MultipartBodyPublisher build() {
      var partsCopy = List.copyOf(parts);
      requireState(!partsCopy.isEmpty(), "at least one part must be added");
      var localMediaType = mediaType;
      if (!localMediaType.parameters().containsKey(BOUNDARY_ATTRIBUTE)) {
        localMediaType =
            localMediaType.withParameter(BOUNDARY_ATTRIBUTE, UUID.randomUUID().toString());
      }
      return new MultipartBodyPublisher(partsCopy, localMediaType);
    }

    @CanIgnoreReturnValue
    private static String requireValidBoundary(String boundary) {
      requireArgument(
          boundary.length() <= MAX_BOUNDARY_LENGTH && !boundary.isEmpty(),
          "illegal boundary length: %s",
          boundary.length());
      requireArgument(
          BOUNDARY_MATCHER.allMatch(boundary) && !boundary.endsWith(" "),
          "illegal boundary: '%s'",
          boundary);
      return boundary;
    }

    @CanIgnoreReturnValue
    private static MediaType requireValidMediaType(MediaType mediaType) {
      requireArgument(
          mediaType.type().equals("multipart"), "Not a multipart type: %s", mediaType.type());
      var boundary = mediaType.parameters().get(BOUNDARY_ATTRIBUTE);
      if (boundary != null) {
        requireValidBoundary(boundary);
      }
      return mediaType;
    }

    private static HttpHeaders getFormHeaders(String name, @Nullable String filename) {
      var contentDisposition = new StringBuilder();
      appendEscaped(contentDisposition.append("form-data; name="), name);
      if (filename != null) {
        appendEscaped(contentDisposition.append("; filename="), filename);
      }
      return HttpHeaders.of(
          Map.of("Content-Disposition", List.of(contentDisposition.toString())), (__, ___) -> true);
    }

    private static void appendEscaped(StringBuilder target, String field) {
      target.append("\"");
      for (int i = 0; i < field.length(); i++) {
        char c = field.charAt(i);
        if (c == '\\' || c == '\"') {
          target.append('\\');
        }
        target.append(c);
      }
      target.append("\"");
    }

    private static MediaType probeMediaType(Path file) {
      try {
        var contentType = Files.probeContentType(file);
        if (contentType != null) {
          return MediaType.parse(contentType);
        }
      } catch (IOException ignored) {
        // Fallback to application/octet-stream.
      }
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }

  /** Listener for changes in the sequence of parts during transmission. */
  interface PartSequenceListener {
    void onNextPart(Part part);

    void onSequenceCompletion();

    default PartSequenceListener guarded() {
      return new PartSequenceListener() {
        @Override
        public void onNextPart(Part part) {
          try {
            PartSequenceListener.this.onNextPart(part);
          } catch (Throwable e) {
            logger.log(Level.WARNING, "exception thrown by PartSequenceListener::onNextPart", e);
          }
        }

        @Override
        public void onSequenceCompletion() {
          try {
            PartSequenceListener.this.onSequenceCompletion();
          } catch (Throwable e) {
            logger.log(
                Level.WARNING, "exception thrown by PartSequenceListener::onSequenceCompletion", e);
          }
        }
      };
    }

    static void register(Subscription subscription, PartSequenceListener listener) {
      requireArgument(
          subscription instanceof MultipartSubscription, "not a multipart subscription");
      ((MultipartSubscription) subscription).registerListener(listener);
    }
  }

  /** Strategy for appending the boundary across parts. */
  private enum BoundaryAppender {
    FIRST("--", "\r\n"),
    MIDDLE("\r\n--", "\r\n"),
    LAST("\r\n--", "--\r\n");

    private final String prefix;
    private final String suffix;

    BoundaryAppender(String prefix, String suffix) {
      this.prefix = prefix;
      this.suffix = suffix;
    }

    void append(StringBuilder target, String boundary) {
      target.append(prefix).append(boundary).append(suffix);
    }

    static BoundaryAppender get(int partIndex, int partsSize) {
      return partIndex == 0 ? FIRST : (partIndex < partsSize ? MIDDLE : LAST);
    }
  }

  private static final class MultipartSubscription
      extends AbstractPollableSubscription<ByteBuffer> {
    private static final VarHandle PART_SUBSCRIBER;

    static {
      try {
        PART_SUBSCRIBER =
            MethodHandles.lookup()
                .findVarHandle(MultipartSubscription.class, "partSubscriber", Subscriber.class);
      } catch (NoSuchFieldException | IllegalAccessException e) {
        throw new ExceptionInInitializerError(e);
      }
    }

    /**
     * A sentinel value indicating no more parts are to be subscribed to. This protects against race
     * conditions that would otherwise occur if a thread tries to abort() while another tries to
     * advancePart(), which might lead to a newly subscribed part being missed by abort().
     */
    private static final Subscriber<ByteBuffer> CANCELLED =
        new Subscriber<>() {
          @Override
          public void onSubscribe(Subscription subscription) {}

          @Override
          public void onNext(ByteBuffer item) {}

          @Override
          public void onError(Throwable throwable) {}

          @Override
          public void onComplete() {}
        };

    private final String boundary;
    private final List<Part> parts;
    private int partIndex;
    private boolean complete;

    /** A subscriber to the currently streaming part. */
    private volatile @MonotonicNonNull Subscriber<ByteBuffer> partSubscriber;

    private final List<PartSequenceListener> listeners = new CopyOnWriteArrayList<>();

    MultipartSubscription(
        String boundary, List<Part> parts, Subscriber<? super ByteBuffer> downstream) {
      super(downstream, FlowSupport.SYNC_EXECUTOR);
      this.boundary = boundary;
      this.parts = parts;
    }

    void registerListener(PartSequenceListener listener) {
      listeners.add(listener.guarded());
    }

    @Override
    protected @Nullable ByteBuffer poll() {
      var subscriber = partSubscriber;
      if (subscriber instanceof PartSubscriber) {
        var next = ((PartSubscriber) subscriber).poll();
        if (next != PartSubscriber.END_OF_PART) {
          return next;
        }
      }
      return advancePart();
    }

    @Override
    protected boolean isComplete() {
      return complete;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void abort(boolean flowInterrupted) {
      var subscriber = (Subscriber<ByteBuffer>) PART_SUBSCRIBER.getAndSet(this, CANCELLED);
      if (subscriber instanceof PartSubscriber) {
        ((PartSubscriber) subscriber).abort(flowInterrupted);
      }
    }

    /** Advances the currently streaming part and returns its multipart metadata. */
    private @Nullable ByteBuffer advancePart() {
      var metadata = new StringBuilder();
      if (partIndex < parts.size()) {
        var part = parts.get(partIndex);
        if (!subscribeTo(part.bodyPublisher())) {
          return null;
        }
        BoundaryAppender.get(partIndex, parts.size()).append(metadata, boundary);
        appendPartHeaders(metadata, part);
        metadata.append("\r\n");
        partIndex++;
        listeners.forEach(listener -> listener.onNextPart(part));
      } else if (partIndex == parts.size()) {
        BoundaryAppender.LAST.append(metadata, boundary);
        partIndex++;
        complete = true;
        partSubscriber = CANCELLED; // Race against abort() here is OK.
        listeners.forEach(PartSequenceListener::onSequenceCompletion);
      } else {
        return null;
      }
      return UTF_8.encode(CharBuffer.wrap(metadata));
    }

    private boolean subscribeTo(BodyPublisher bodyPublisher) {
      var currentSubscriber = partSubscriber;
      PartSubscriber nextSubscriber;
      if (currentSubscriber != CANCELLED
          && PART_SUBSCRIBER.compareAndSet(
              this, currentSubscriber, nextSubscriber = new PartSubscriber(this))) {
        bodyPublisher.subscribe(nextSubscriber);
        return true;
      }
      return false;
    }
  }

  private static final class PartSubscriber implements Subscriber<ByteBuffer> {
    static final ByteBuffer END_OF_PART = ByteBuffer.allocate(0);

    private final MultipartSubscription downstream;
    private final Upstream upstream = new Upstream();
    private final Prefetcher prefetcher = new Prefetcher();
    private final ConcurrentLinkedQueue<ByteBuffer> buffers = new ConcurrentLinkedQueue<>();

    PartSubscriber(MultipartSubscription downstream) {
      this.downstream = downstream;
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      requireNonNull(subscription);
      if (upstream.setOrCancel(subscription)) {
        prefetcher.initialize(upstream);
      }
    }

    @Override
    public void onNext(ByteBuffer item) {
      buffers.add(item);
      downstream.fireOrKeepAliveOnNext();
    }

    @Override
    public void onError(Throwable throwable) {
      requireNonNull(throwable);
      abort(false);
      downstream.fireOrKeepAliveOnError(throwable);
    }

    @Override
    public void onComplete() {
      abort(false);
      buffers.add(END_OF_PART);
      downstream.fireOrKeepAlive();
    }

    void abort(boolean flowInterrupted) {
      upstream.cancel(flowInterrupted);
    }

    @Nullable ByteBuffer poll() {
      var next = buffers.poll();
      if (next != null && next != END_OF_PART) {
        prefetcher.update(upstream);
      }
      return next;
    }
  }
}
