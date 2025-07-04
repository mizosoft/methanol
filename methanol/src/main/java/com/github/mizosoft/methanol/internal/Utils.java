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

package com.github.mizosoft.methanol.internal;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.text.HttpCharMatchers.FIELD_VALUE_MATCHER;
import static com.github.mizosoft.methanol.internal.text.HttpCharMatchers.TOKEN_MATCHER;

import com.github.mizosoft.methanol.BodyAdapter.Hints;
import com.github.mizosoft.methanol.MediaType;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Miscellaneous utilities. */
public class Utils {
  private static final Clock SYSTEM_MILLIS_UTC = Clock.tickMillis(ZoneOffset.UTC);

  private static final int DEFAULT_BUFFER_SIZE = 16 * 1024;
  public static final int BUFFER_SIZE;

  static {
    int bufferSize = Integer.getInteger("jdk.httpclient.bufsize", DEFAULT_BUFFER_SIZE);
    if (bufferSize <= 0) {
      bufferSize = DEFAULT_BUFFER_SIZE;
    }
    BUFFER_SIZE = bufferSize;
  }

  private Utils() {}

  public static boolean isValidToken(CharSequence token) {
    return token.length() != 0 && TOKEN_MATCHER.allMatch(token);
  }

  public static boolean isValidHeaderName(String name) {
    // Consider HTTP2 pseudo-header fields (e.g. ':status').
    return name.startsWith(":")
        ? isValidToken(CharBuffer.wrap(name, 1, name.length()))
        : isValidToken(name);
  }

  @CanIgnoreReturnValue
  public static <S extends CharSequence> S requireValidToken(S token) {
    requireArgument(isValidToken(token), "illegal token: '%s'", token);
    return token;
  }

  @CanIgnoreReturnValue
  public static String requireValidHeaderName(String name) {
    requireArgument(isValidHeaderName(name), "illegal header name: '%s'", name);
    return name;
  }

  @CanIgnoreReturnValue
  public static String requireValidHeaderValue(String value) {
    requireArgument(FIELD_VALUE_MATCHER.allMatch(value), "illegal header value: '%s'", value);
    return value;
  }

  public static void requireValidHeader(String name, String value) {
    requireValidHeaderName(name);
    requireValidHeaderValue(value);
  }

  @CanIgnoreReturnValue
  public static Duration requirePositiveDuration(Duration duration) {
    requireArgument(
        !(duration.isNegative() || duration.isZero()), "non-positive duration: %s", duration);
    return duration;
  }

  @CanIgnoreReturnValue
  public static Duration requireNonNegativeDuration(Duration duration) {
    requireArgument(!duration.isNegative(), "negative duration: %s", duration);
    return duration;
  }

  public static int copyRemaining(ByteBuffer src, ByteBuffer dst) {
    int toCopy = Math.min(src.remaining(), dst.remaining());
    int srcLimit = src.limit();
    src.limit(src.position() + toCopy);
    dst.put(src);
    src.limit(srcLimit);
    return toCopy;
  }

  public static ByteBuffer copy(ByteBuffer buffer) {
    return ByteBuffer.allocate(buffer.remaining()).put(buffer).flip();
  }

  public static Clock systemMillisUtc() {
    return SYSTEM_MILLIS_UTC;
  }

  /**
   * Tries to clone & rethrow the given {@code ExecutionException}'s cause to capture the current
   * stack trace, or throws an {@code IOException} with the same cause as the given exception if
   * cloning is not possible. Return type is only declared for this method to be conveniently used
   * in a {@code throw} statement.
   */
  private static RuntimeException rethrowExecutionExceptionCause(ExecutionException exception)
      throws IOException, InterruptedException {
    var clonedCause = tryCloneThrowable(exception.getCause());
    if (clonedCause instanceof RuntimeException) {
      throw (RuntimeException) clonedCause;
    } else if (clonedCause instanceof Error) {
      throw (Error) clonedCause;
    } else if (clonedCause instanceof IOException) {
      throw (IOException) clonedCause;
    } else if (clonedCause instanceof InterruptedException) {
      throw (InterruptedException) clonedCause;
    } else {
      throw new IOException(exception.getCause());
    }
  }

  private static @Nullable Throwable tryCloneThrowable(@Nullable Throwable t) {
    if (t == null) {
      return null;
    }

    // Clone the main cause in a CompletionException|ExecutionException chain.
    var throwableToClone = getDeepCompletionCause(t);

    // Don't bother cloning if caller can't rethrow the cloned exception.
    if (!(throwableToClone instanceof RuntimeException
        || throwableToClone instanceof Error
        || throwableToClone instanceof IOException
        || throwableToClone instanceof InterruptedException)) {
      return null;
    }

    var throwableClass = throwableToClone.getClass();
    var message = throwableToClone.getMessage();
    try {
      return throwableClass
          .getConstructor(String.class, Throwable.class)
          .newInstance(message, throwableToClone);
    } catch (ReflectiveOperationException ignored) {
      // Try message-only constructor.
    }

    try {
      var cloned = throwableClass.getConstructor(String.class).newInstance(message);
      try {
        return cloned.initCause(throwableToClone);
      } catch (IllegalStateException ignored) {
        return null;
      }
    } catch (ReflectiveOperationException ignored) {
      // Try cause-only constructor.
    }

    try {
      return throwableClass.getConstructor(Throwable.class).newInstance(throwableToClone);
    } catch (ReflectiveOperationException ignored) {
      // Try zero-arg constructor.
    }

    try {
      var cloned = throwableClass.getConstructor().newInstance();
      try {
        return cloned.initCause(throwableToClone);
      } catch (IllegalStateException ignored) {
        return null;
      }
    } catch (ReflectiveOperationException ignored) {
      // Make caller fallback to wrapping in an IOException.
    }
    return null;
  }

  public static Throwable getDeepCompletionCause(Throwable t) {
    var cause = t;
    while (cause instanceof CompletionException || cause instanceof ExecutionException) {
      var deeperCause = cause.getCause();
      if (deeperCause == null) {
        break;
      }
      cause = deeperCause;
    }
    return cause;
  }

  public static <T> T get(Future<T> future) throws IOException, InterruptedException {
    try {
      return future.get();
    } catch (ExecutionException e) {
      throw rethrowExecutionExceptionCause(e);
    } catch (InterruptedException e) {
      future.cancel(true);
      throw e;
    }
  }

  public static <T> T getIo(Future<T> future) throws IOException {
    try {
      return get(future);
    } catch (InterruptedException e) {
      throw toInterruptedIOException(e);
    }
  }

  /**
   * From RFC 7230 section 3.2.6:
   *
   * <p>"A sender SHOULD NOT generate a quoted-pair in a quoted-string except where necessary to
   * quote DQUOTE and backslash octets occurring within that string."
   */
  public static String escapeAndQuoteValueIfNeeded(String value) {
    // If value is already a token then it doesn't need quoting.
    return isValidToken(value) ? value : escapeAndQuote(value);
  }

  private static String escapeAndQuote(String value) {
    var escaped = new StringBuilder();
    escaped.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (c == '"' || c == '\\') {
        escaped.append('\\');
      }
      escaped.append(c);
    }
    escaped.append('"');
    return escaped.toString();
  }

  public static boolean startsWithIgnoreCase(String source, String prefix) {
    return source.regionMatches(true, 0, prefix, 0, prefix.length());
  }

  public static CompletionException toCompletionException(Throwable t) {
    return t instanceof CompletionException
        ? ((CompletionException) t)
        : new CompletionException(t);
  }

  public static InterruptedIOException toInterruptedIOException(InterruptedException e) {
    return (InterruptedIOException) new InterruptedIOException().initCause(e);
  }

  public static long remaining(List<ByteBuffer> buffers) {
    long remaining = 0;
    for (var buffer : buffers) {
      remaining += buffer.remaining();
    }
    return remaining;
  }

  public static long remaining(ByteBuffer[] buffers) {
    long remaining = 0;
    for (var buffer : buffers) {
      remaining += buffer.remaining();
    }
    return remaining;
  }

  public static Hints hintsOf(@Nullable MediaType mediaType) {
    return mediaType != null ? Hints.of(mediaType) : Hints.empty();
  }

  public static String toStringIdentityPrefix(Object object) {
    return object.getClass().getSimpleName() + "@" + Integer.toHexString(object.hashCode());
  }

  public static String forwardingObjectToString(Object forwardingObject, Object delegate) {
    return toStringIdentityPrefix(forwardingObject) + "[delegate=" + delegate + "]";
  }
}
