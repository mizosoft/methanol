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
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.flow.Demand;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.internal.flow.SchedulableSubscription;
import com.github.mizosoft.methanol.internal.text.CharMatcher;
import java.io.FileNotFoundException;
import java.io.IOException;
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
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@code BodyPublisher} implementing the multipart request type.
 *
 * @see <a href="https://tools.ietf.org/html/rfc2046#section-5.1">RFC 2046 Multipart Media Type</a>
 */
@SuppressWarnings("ReferenceEquality") // ByteBuffer sentinel values
public class MultipartBodyPublisher implements MimeBodyPublisher {

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
    return mediaType.parameters().get(BOUNDARY_ATTRIBUTE); // A boundary attr is guaranteed to exist
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
    MultipartSubscription subscription = new MultipartSubscription(this, subscriber);
    subscription.signal();
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

    private static final MediaType TEXT_PLAIN = MediaType.of("text", "plain");
    private static final MediaType OCTET_STREAM = MediaType.of("application", "octet-stream");

    // boundary     := 0*69<bchars> bcharnospace
    // bchars       := bcharnospace / " "
    // bcharnospace := DIGIT / ALPHA / "'" / "(" / ")" /
    //                 "+" / "_" / "," / "-" / "." ?
    //                 "/" / ":" / "=" / "?"
    private static final CharMatcher BOUNDARY_MATCHER = chars("'()+_,-./:=? ").or(alphaNum());

    private static final int MAX_BOUNDARY_LENGTH = 70;

    private static final String MULTIPART_TYPE = "multipart";
    private static final String DEFAULT_SUBTYPE = "form-data";

    private final List<Part> parts;
    private MediaType mediaType;

    Builder() {
      parts = new ArrayList<>();
      mediaType = MediaType.of(MULTIPART_TYPE, DEFAULT_SUBTYPE);
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
      MimeBodyPublisher publisher =
          MoreBodyPublishers.ofMediaType(
              BodyPublishers.ofString(value.toString(), charset), TEXT_PLAIN.withCharset(charset));
      return formPart(name, publisher);
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
      List<Part> parts = List.copyOf(this.parts);
      requireState(!parts.isEmpty(), "at least one part should be added");
      MediaType mediaType = this.mediaType;
      if (!mediaType.parameters().containsKey(BOUNDARY_ATTRIBUTE)) {
        mediaType = mediaType.withParameter(BOUNDARY_ATTRIBUTE, UUID.randomUUID().toString());
      }
      return new MultipartBodyPublisher(parts, mediaType);
    }

    private static String validateBoundary(String boundary) {
      requireArgument(
          boundary.length() <= MAX_BOUNDARY_LENGTH && !boundary.isEmpty(),
          "illegal boundary length: %s",
          boundary.length());
      requireArgument(
          BOUNDARY_MATCHER.allMatch(boundary) && !boundary.endsWith(" "),
          "illegal boundary: %s",
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
      return OCTET_STREAM;
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

  private static final class MultipartSubscription extends SchedulableSubscription {

    private final Subscriber<? super ByteBuffer> downstream;
    private final Demand demand;
    private @MonotonicNonNull PartSubscriber partSubscriber;
    private boolean subscribed;
    private boolean complete;
    private volatile boolean cancelled;
    private volatile boolean illegalRequest;

    // multipart-related stuff
    private final String boundary;
    private final List<Part> parts;
    private int partIndex;

    MultipartSubscription(
        MultipartBodyPublisher upstream, Subscriber<? super ByteBuffer> downstream) {
      // Use synchronous execution. It seems that the HTTP client publisher
      // implementations are all synchronous and exposing an Executor option
      // will only lead to more API weight with unnecessary confusion.
      super(FlowSupport.SYNC_EXECUTOR);
      this.downstream = downstream;
      demand = new Demand();
      boundary = upstream.boundary();
      parts = upstream.parts();
    }

    @Override
    public void request(long n) {
      if (!cancelled) {
        if ((n > 0 && demand.increase(n)) || (illegalRequest = n <= 0)) {
          signal();
        }
      }
    }

    @Override
    public void cancel() {
      doCancel(true);
    }

    // does cancellation with optionally cancelling current part
    void doCancel(boolean cancelPart) {
      if (!cancelled) { // Races are OK
        cancelled = true;
        if (cancelPart) {
          signal(); // part is cancelled inline with drain logic
        }
      }
    }

    @Override
    protected void drain() {
      if (cancelled) {
        // It's either a false alarm or a part's subscription, if
        // any, needs to be cancelled. In case it's a false alarm,
        // the part's subscription will have probably been NOOPed
        // already so it won't harm NOOPing/cancelling it again
        PartSubscriber subscriber = partSubscriber;
        if (subscriber != null) {
          subscriber.clearUpstream(true);
        }
        stop(); // Prevent further runs
      } else {
        // Logic is similar to AsyncBodyDecoder

        Subscriber<? super ByteBuffer> s = downstream;
        subscribeOnDrain(s);
        for (long d = demand.current(), e = 0L; !cancelled; ) {
          Throwable error = pendingError();
          if (error != null) {
            doCancel(false);
            s.onError(error);
          } else if (illegalRequest) {
            doCancel(true);
            s.onError(FlowSupport.illegalRequest());
          } else {
            long f = emitItems(s, d - e);
            e += f;
            d = demand.current();
            if (e == d || f == 0L) {
              if (e > 0) {
                d = demand.decreaseAndGet(e);
              }
              if (d == 0L || f == 0L) {
                break;
              }
              e = 0L;
            }
          }
        }
      }
    }

    private void subscribeOnDrain(Subscriber<? super ByteBuffer> s) {
      if (!subscribed && !cancelled) {
        subscribed = true;
        try {
          s.onSubscribe(this);
        } catch (Throwable t) {
          cancel();
          s.onError(t);
        }
      }
    }

    private long emitItems(Subscriber<? super ByteBuffer> s, long demand) {
      long x;
      for (x = 0L; x < demand && !cancelled; x++) {
        try {
          ByteBuffer buffer = pollNext();
          if (buffer == null) {
            break; // No items, give up.
          }
          s.onNext(buffer);
        } catch (Throwable t) {
          doCancel(true);
          s.onError(t);
          break;
        }
        if (complete) { // pollNext() completed
          doCancel(false);
          s.onComplete();
        }
      }
      return x;
    }

    private @Nullable Throwable pendingError() {
      PartSubscriber current = partSubscriber;
      return current != null ? current.pendingError : null;
    }

    private @Nullable ByteBuffer pollNext() {
      PartSubscriber subscriber = partSubscriber;
      if (subscriber != null) {
        ByteBuffer next = subscriber.poll();
        if (next != PartSubscriber.END_OF_PART) {
          return next;
        }
      }
      return peekPart();
    }

    /**
     * Subscribes to the next part and returns the part's heading (boundary + headers). If there are
     * no more parts then the end boundary is returned.
     */
    private ByteBuffer peekPart() {
      int idx = partIndex;
      int size = parts.size();
      StringBuilder heading = new StringBuilder();
      BoundaryAppender.get(idx, size).append(heading, boundary);
      if (idx < size) {
        Part part = parts.get(idx++);
        appendPartHeaders(heading, part);
        heading.append("\r\n");
        partSubscriber = subscribeToPart(part);
        partIndex = idx;
      } else {
        complete = true;
      }
      return UTF_8.encode(CharBuffer.wrap(heading));
    }

    private PartSubscriber subscribeToPart(Part part) {
      PartSubscriber subscriber = new PartSubscriber(this::signal);
      part.bodyPublisher().subscribe(subscriber);
      return subscriber;
    }
  }

  private static final class PartSubscriber implements Subscriber<ByteBuffer> {

    static final ByteBuffer END_OF_PART = ByteBuffer.allocate(0);

    private final Runnable onSignal; // Hook to be called when upstream signals arrive
    private final ConcurrentLinkedQueue<ByteBuffer> buffers;
    private final AtomicReference<@Nullable Subscription> upstream;
    private final int prefetch;
    private final int prefetchThreshold;
    private volatile @MonotonicNonNull Throwable pendingError;
    private volatile int upstreamWindow;

    PartSubscriber(Runnable onSignal) {
      this.onSignal = onSignal;
      buffers = new ConcurrentLinkedQueue<>();
      upstream = new AtomicReference<>();
      prefetch = FlowSupport.prefetch();
      prefetchThreshold = FlowSupport.prefetchThreshold();
    }

    @Override
    public void onSubscribe(Subscription subscription) {
      requireNonNull(subscription);
      if (upstream.compareAndSet(null, subscription)) {
        // The only possible concurrent access to `incoming` applies here.
        // But the operation need not be atomic as other reads/writes
        // are done serially when ByteBuffers are polled, which is only
        // possible after this write to `incoming`.
        upstreamWindow = prefetch;
        subscription.request(prefetch);
      } else {
        subscription.cancel();
      }
    }

    @Override
    public void onNext(ByteBuffer item) {
      requireNonNull(item);
      buffers.offer(item);
      onSignal.run();
    }

    @Override
    public void onError(Throwable throwable) {
      requireNonNull(throwable);
      complete(throwable);
    }

    @Override
    public void onComplete() {
      complete(null);
    }

    @Nullable
    ByteBuffer poll() {
      ByteBuffer next = buffers.peek();
      if (next != null && next != END_OF_PART) {
        buffers.poll();
        // See if window should be brought back to prefetch
        Subscription sub = upstream.get();
        if (sub != null) {
          int update = Math.max(0, upstreamWindow - 1);
          if (update <= prefetchThreshold) {
            upstreamWindow = prefetch;
            sub.request(prefetch - update);
          } else {
            upstreamWindow = update;
          }
        }
      }
      return next;
    }

    void clearUpstream(boolean cancel) {
      if (cancel) {
        Subscription sub = upstream.getAndSet(FlowSupport.NOOP_SUBSCRIPTION);
        if (sub != null) {
          sub.cancel();
        }
      } else {
        // Avoid the CAS
        upstream.set(FlowSupport.NOOP_SUBSCRIPTION);
      }
    }

    private void complete(@Nullable Throwable error) {
      clearUpstream(false);
      if (error != null) {
        pendingError = error;
      } else {
        buffers.offer(END_OF_PART);
      }
      onSignal.run();
    }
  }
}
