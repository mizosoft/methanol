/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static com.github.mizosoft.methanol.internal.text.CharMatcher.alphaNum;
import static com.github.mizosoft.methanol.internal.text.CharMatcher.chars;
import static com.github.mizosoft.methanol.internal.text.HttpCharMatchers.BOUNDARY_MATCHER;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.Validate;
import com.github.mizosoft.methanol.internal.flow.AbstractSubscription;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.Prefetcher;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import com.github.mizosoft.methanol.internal.text.CharMatcher;
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
 * A {@code BodyPublisher} implementing the multipart request type.
 *
 * @see <a href="https://tools.ietf.org/html/rfc2046#section-5.1">RFC 2046 Multipart Media Type</a>
 */
@SuppressWarnings("ReferenceEquality") // ByteBuffer sentinel values
public final class MultipartBodyPublisher implements MimeBodyPublisher {
  private static final Logger logger = System.getLogger(MultipartBodyPublisher.class.getName());

  private static final long UNKNOWN_LENGTH = -1;
  private static final long UNINITIALIZED_LENGTH = -2;

  private static final String BOUNDARY_ATTRIBUTE = "boundary";

  private final List<Part> parts;
  private final MediaType mediaType;
  private long contentLength;

  private MultipartBodyPublisher(List<Part> parts, MediaType mediaType) {
    this.parts = parts;
    this.mediaType = mediaType;
    contentLength = UNINITIALIZED_LENGTH;
  }

  /** Returns the boundary of this multipart body. */
  public String boundary() {
    return Validate.castNonNull(
        mediaType.parameters().get(BOUNDARY_ATTRIBUTE)); // A boundary attr is guaranteed to exist
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
    long len = contentLength;
    if (len == UNINITIALIZED_LENGTH) {
      len = computeLength();
      contentLength = len;
    }
    return len;
  }

  @Override
  public void subscribe(Subscriber<? super ByteBuffer> subscriber) {
    requireNonNull(subscriber);
    new MultipartSubscription(this, subscriber).signal(true); // apply onSubscribe
  }

  private long computeLength() {
    long lengthOfParts = 0L;
    String boundary = boundary();
    StringBuilder headings = new StringBuilder();
    for (int i = 0, sz = parts.size(); i < sz; i++) {
      Part part = parts.get(i);
      long partLength = part.bodyPublisher().contentLength();
      if (partLength < 0) {
        return UNKNOWN_LENGTH;
      }
      lengthOfParts += partLength;
      // Append preceding boundary + part header
      BoundaryAppender.get(i, sz).append(headings, boundary);
      appendPartHeaders(headings, part);
      headings.append("\r\n");
    }
    BoundaryAppender.LAST.append(headings, boundary);
    // Use headings' utf8-encoded length
    return lengthOfParts + UTF_8.encode(CharBuffer.wrap(headings)).remaining();
  }

  private static void appendPartHeaders(StringBuilder target, Part part) {
    part.headers().map().forEach((n, vs) -> vs.forEach(v -> appendHeader(target, n, v)));
    BodyPublisher publisher = part.bodyPublisher();
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

  /** Represents a part in a multipart request body. */
  public static final class Part {

    private static final CharMatcher TOKEN_MATCHER = chars("!#$%&'*+-.^_`|~").or(alphaNum());

    private final HttpHeaders headers;
    private final BodyPublisher bodyPublisher;

    Part(HttpHeaders headers, BodyPublisher bodyPublisher) {
      requireNonNull(headers, "headers");
      requireNonNull(bodyPublisher, "bodyPublisher");
      validateHeaderNames(headers.map().keySet(), bodyPublisher);
      this.headers = headers;
      this.bodyPublisher = bodyPublisher;
    }

    /** Returns the headers of this part. */
    public HttpHeaders headers() {
      return headers;
    }

    /** Returns the {@code BodyPublisher} that publishes this part's content. */
    public BodyPublisher bodyPublisher() {
      return bodyPublisher;
    }

    /**
     * Creates and returns a new {@code Part} with the given headers and body publisher.
     *
     * @param headers the part's headers
     * @param bodyPublisher the part's body publisher
     * @throws IllegalArgumentException if the name of any of the given headers is invalid or if a
     *     {@code Content-Type} header is present but the given publisher is a {@link
     *     MimeBodyPublisher}
     */
    public static Part create(HttpHeaders headers, BodyPublisher bodyPublisher) {
      return new Part(headers, bodyPublisher);
    }

    private static void validateHeaderNames(Set<String> names, BodyPublisher publisher) {
      requireArgument(
          !(names.contains("Content-Type") && publisher instanceof MimeBodyPublisher),
          "unexpected Content-Type header");
      for (String name : names) {
        requireArgument(
            TOKEN_MATCHER.allMatch(name) && !name.isEmpty(), "illegal header name: %s", name);
      }
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

    private static final String MULTIPART_TYPE = "multipart";
    private static final String FORM_DATA_SUBTYPE = "form-data";

    private final List<Part> parts;
    private MediaType mediaType;

    Builder() {
      parts = new ArrayList<>();
      mediaType = MediaType.of(MULTIPART_TYPE, FORM_DATA_SUBTYPE);
    }

    /**
     * Sets the boundary of the multipart body.
     *
     * @throws IllegalArgumentException if the boundary is invalid
     */
    public Builder boundary(String boundary) {
      requireNonNull(boundary);
      mediaType = mediaType.withParameter(BOUNDARY_ATTRIBUTE, validateBoundary(boundary));
      return this;
    }

    /**
     * Sets the media type of the multipart body. If the given media type has a boundary parameter
     * then it will be used as the body's boundary.
     *
     * @param mediaType the multipart media type
     * @throws IllegalArgumentException if the given media type is not a multipart type or if it has
     *     an invalid boundary parameter
     */
    public Builder mediaType(MediaType mediaType) {
      requireNonNull(mediaType);
      this.mediaType = checkMediaType(mediaType);
      return this;
    }

    /**
     * Adds the given part.
     *
     * @param part the part
     */
    public Builder part(Part part) {
      requireNonNull(part);
      parts.add(part);
      return this;
    }

    /**
     * Adds a form field with the given name and body.
     *
     * @param name the field's name
     * @param bodyPublisher the field's body publisher
     */
    public Builder formPart(String name, BodyPublisher bodyPublisher) {
      requireNonNull(name, "name");
      requireNonNull(bodyPublisher, "body");
      return part(Part.create(getFormHeaders(name, null), bodyPublisher));
    }

    /**
     * Adds a form field with the given name, filename and body.
     *
     * @param name the field's name
     * @param filename the field's filename
     * @param body the field's body publisher
     */
    public Builder formPart(String name, String filename, BodyPublisher body) {
      requireNonNull(name, "name");
      requireNonNull(filename, "filename");
      requireNonNull(body, "body");
      return part(Part.create(getFormHeaders(name, filename), body));
    }

    /**
     * Adds a {@code text/plain} form field with the given name and value. {@code UTF-8} is used for
     * encoding the field's body.
     *
     * @param name the field's name
     * @param value an object whose string representation is used as the value
     */
    public Builder textPart(String name, Object value) {
      return textPart(name, value, UTF_8);
    }

    /**
     * Adds a {@code text/plain} form field with the given name and value using the given charset
     * for encoding the field's body.
     *
     * @param name the field's name
     * @param value an object whose string representation is used as the value
     * @param charset the charset for encoding the field's body
     */
    public Builder textPart(String name, Object value, Charset charset) {
      requireNonNull(name, "name");
      requireNonNull(value, "value");
      requireNonNull(charset, "charset");
      return formPart(name, BodyPublishers.ofString(value.toString(), charset));
    }

    /**
     * Adds a file form field with the given name and file. The field's filename property will be
     * that of the given path's {@link Path#getFileName() filename compontent}. The given path will
     * be used to {@link Files#probeContentType(Path) probe} the part's media type. If the probing
     * operation fails, either by throwing an exception or returning {@code null}, {@code
     * application/octet-stream} will be used.
     *
     * @param name the field's name
     * @param file the file's path
     * @throws FileNotFoundException if a file with the given path cannot be found
     */
    public Builder filePart(String name, Path file) throws FileNotFoundException {
      return filePart(name, file, probeMediaType(file));
    }

    /**
     * Adds a file form field with given name, file and media type. The field's filename property
     * will be that of the given path's {@link Path#getFileName() filename compontent}.
     *
     * @param name the field's name
     * @param file the file's path
     * @param mediaType the part's media type
     * @throws FileNotFoundException if a file with the given path cannot be found
     */
    public Builder filePart(String name, Path file, MediaType mediaType)
        throws FileNotFoundException {
      requireNonNull(name, "name");
      requireNonNull(file, "file");
      requireNonNull(mediaType, "mediaType");
      @Nullable Path filenameComponent = file.getFileName();
      String filename = filenameComponent != null ? filenameComponent.toString() : "";
      MimeBodyPublisher publisher =
          MoreBodyPublishers.ofMediaType(BodyPublishers.ofFile(file), mediaType);
      return formPart(name, filename, publisher);
    }

    /**
     * Creates and returns a new {@code MultipartBodyPublisher} with a snapshot of the added parts.
     * If no boundary was previously set, a randomly generated one is used.
     *
     * @throws IllegalStateException if no part was added
     */
    public MultipartBodyPublisher build() {
      List<Part> addedParts = List.copyOf(parts);
      requireState(!addedParts.isEmpty(), "at least one part should be added");
      MediaType localMediaType = mediaType;
      if (!localMediaType.parameters().containsKey(BOUNDARY_ATTRIBUTE)) {
        localMediaType =
            localMediaType.withParameter(BOUNDARY_ATTRIBUTE, UUID.randomUUID().toString());
      }
      return new MultipartBodyPublisher(addedParts, localMediaType);
    }

    private static String validateBoundary(String boundary) {
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

    private static MediaType checkMediaType(MediaType mediaType) {
      requireArgument(
          MULTIPART_TYPE.equals(mediaType.type()), "not a multipart type: %s", mediaType.type());
      String boundary = mediaType.parameters().get(BOUNDARY_ATTRIBUTE);
      if (boundary != null) {
        validateBoundary(boundary);
      }
      return mediaType;
    }

    private static HttpHeaders getFormHeaders(String name, @Nullable String filename) {
      StringBuilder disposition = new StringBuilder();
      appendEscaped(disposition.append("form-data; name="), name);
      if (filename != null) {
        appendEscaped(disposition.append("; filename="), filename);
      }
      return HttpHeaders.of(
          Map.of("Content-Disposition", List.of(disposition.toString())), (n, v) -> true);
    }

    private static void appendEscaped(StringBuilder target, String field) {
      target.append("\"");
      for (int i = 0, len = field.length(); i < len; i++) {
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
        String contentType = Files.probeContentType(file);
        if (contentType != null) {
          return MediaType.parse(contentType);
        }
      } catch (IOException ignored) {
        // Use OCTET_STREAM
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
          } catch (Throwable error) {
            logger.log(
                Level.WARNING, "exception thrown by PartSequenceListener::onNextPart", error);
          }
        }

        @Override
        public void onSequenceCompletion() {
          try {
            PartSequenceListener.this.onSequenceCompletion();
          } catch (Throwable error) {
            logger.log(
                Level.WARNING,
                "exception thrown by PartSequenceListener::onSequenceCompletion",
                error);
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
      return partIndex <= 0 ? FIRST : (partIndex >= partsSize ? LAST : MIDDLE);
    }
  }

  private static final class MultipartSubscription extends AbstractSubscription<ByteBuffer> {

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
     * A tombstone indicating no more parts are to be subscribed to. This protects against race
     * conditions that would otherwise occur if a thread tries to abort() while another tries to
     * nextPartHeaders(), which might lead to a newly subscribed part being missed by abort().
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
    private volatile @MonotonicNonNull Subscriber<ByteBuffer> partSubscriber;
    private int partIndex;
    private boolean complete;

    private final List<PartSequenceListener> listeners = new CopyOnWriteArrayList<>();

    MultipartSubscription(
        MultipartBodyPublisher upstream, Subscriber<? super ByteBuffer> downstream) {
      super(downstream, FlowSupport.SYNC_EXECUTOR);
      boundary = upstream.boundary();
      parts = upstream.parts();
    }

    void registerListener(PartSequenceListener listener) {
      listeners.add(listener.guarded());
    }

    @Override
    protected long emit(Subscriber<? super ByteBuffer> downstream, long emit) {
      long submitted = 0L;
      while (true) {
        ByteBuffer batch;
        if (complete) {
          cancelOnComplete(downstream);
          return 0;
        } else if (submitted >= emit
            || (batch = pollNext()) == null) { // exhausted demand or batches
          return submitted;
        } else if (submitOnNext(downstream, batch)) {
          submitted++;
        } else {
          return 0;
        }
      }
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void abort(boolean flowInterrupted) {
      Subscriber<ByteBuffer> previous =
          (Subscriber<ByteBuffer>) PART_SUBSCRIBER.getAndSet(this, CANCELLED);
      if (previous instanceof PartSubscriber) {
        ((PartSubscriber) previous).abortUpstream(flowInterrupted);
      }
    }

    private @Nullable ByteBuffer pollNext() {
      Subscriber<ByteBuffer> subscriber = partSubscriber;
      if (subscriber instanceof PartSubscriber) { // not cancelled & not null
        ByteBuffer next = ((PartSubscriber) subscriber).pollNext();
        if (next != PartSubscriber.END_OF_PART) {
          return next;
        }
      }
      return subscriber != CANCELLED ? nextPartHeaders() : null;
    }

    private @Nullable ByteBuffer nextPartHeaders() {
      StringBuilder heading = new StringBuilder();
      BoundaryAppender.get(partIndex, parts.size()).append(heading, boundary);
      if (partIndex < parts.size()) {
        Part part = parts.get(partIndex++);
        if (!subscribeToPart(part)) {
          return null;
        }
        appendPartHeaders(heading, part);
        heading.append("\r\n");

        listeners.forEach(listener -> listener.onNextPart(part));
      } else {
        partSubscriber = CANCELLED; // race against abort() here is OK
        complete = true;

        listeners.forEach(PartSequenceListener::onSequenceCompletion);
      }
      return UTF_8.encode(CharBuffer.wrap(heading));
    }

    private boolean subscribeToPart(Part part) {
      PartSubscriber subscriber = new PartSubscriber(this);
      Subscriber<ByteBuffer> current = partSubscriber;
      if (current != CANCELLED && PART_SUBSCRIBER.compareAndSet(this, current, subscriber)) {
        part.bodyPublisher().subscribe(subscriber);
        return true;
      }
      return false;
    }
  }

  private static final class PartSubscriber implements Subscriber<ByteBuffer> {

    static final ByteBuffer END_OF_PART = ByteBuffer.allocate(0);

    private final MultipartSubscription downstream; // for signalling
    private final ConcurrentLinkedQueue<ByteBuffer> buffers;
    private final Upstream upstream;
    private final Prefetcher prefetcher;

    PartSubscriber(MultipartSubscription downstream) {
      this.downstream = downstream;
      buffers = new ConcurrentLinkedQueue<>();
      upstream = new Upstream();
      prefetcher = new Prefetcher();
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      requireNonNull(subscription);
      if (upstream.setOrCancel(subscription)) {
        // The only possible concurrent access to prefetcher applies here.
        // But the operation need not be atomic as other reads/writes
        // are done serially when ByteBuffers are polled, which is only
        // possible after this volatile write.
        prefetcher.initialize(upstream);
      }
    }

    @Override
    public void onNext(ByteBuffer item) {
      requireNonNull(item);
      buffers.offer(item);
      downstream.signal(false);
    }

    @Override
    public void onError(Throwable throwable) {
      requireNonNull(throwable);
      abortUpstream(false);
      downstream.signalError(throwable);
    }

    @Override
    public void onComplete() {
      abortUpstream(false);
      buffers.offer(END_OF_PART);
      downstream.signal(true); // force completion signal
    }

    void abortUpstream(boolean cancel) {
      if (cancel) {
        upstream.cancel();
      } else {
        upstream.clear();
      }
    }

    @Nullable
    ByteBuffer pollNext() {
      ByteBuffer next = buffers.peek();
      if (next != null && next != END_OF_PART) {
        buffers.poll(); // remove
        prefetcher.update(upstream);
      }
      return next;
    }
  }
}
