/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing;

import static java.net.HttpURLConnection.HTTP_OK;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.mizosoft.methanol.ResponseBuilder;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.zip.DeflaterInputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import javax.net.ssl.SSLContext;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

public class TestUtils {
  public static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  /**
   * The number of buffers to use for {@code List<ByteBuffer>} streams. This number matches what's
   * used by the HTTP client.
   */
  public static final int BUFFERS_PER_LIST = 3;

  // For consistency, TIMEOUT_SECONDS must be in sync with junit.jupiter.execution.timeout.default's
  // value set by our testing conventions.
  public static final int TIMEOUT_SECONDS = 4;
  public static final int SLOW_TIMEOUT_SECONDS = 8;
  public static final int VERY_SLOW_TIMEOUT_SECONDS = 16;

  private static final long RANDOM_SEED = 25;

  private TestUtils() {}

  public static Random newRandom() {
    return new Random(RANDOM_SEED);
  }

  public static void awaitUnchecked(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      throw new CompletionException(e);
    }
  }

  public static void awaitUnchecked(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (InterruptedException | BrokenBarrierException e) {
      throw new CompletionException(e);
    }
  }

  public static void shutdown(Executor... executors) {
    for (var e : executors) {
      if (e instanceof ExecutorService) {
        ((ExecutorService) e).shutdown();
      }
    }
  }

  public static byte[] gunzip(byte[] data) {
    try (var in = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static byte[] gzip(String s) {
    var buffer = new ByteArrayOutputStream();
    try (var out = new GZIPOutputStream(buffer)) {
      out.write(s.getBytes(UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return buffer.toByteArray();
  }

  public static byte[] zlibUnwrap(byte[] zlibWrapped) {
    assert zlibWrapped.length > Short.BYTES + Integer.BYTES;
    return Arrays.copyOfRange(zlibWrapped, Short.BYTES, zlibWrapped.length - Integer.BYTES);
  }

  public static byte[] inflate(byte[] data) {
    return inflate0(data, new Inflater());
  }

  private static byte[] inflate0(byte[] data, Inflater inflater) {
    try (var in = new InflaterInputStream(new ByteArrayInputStream(data), inflater)) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    } finally {
      inflater.end();
    }
  }

  public static byte[] deflate(String s) {
    try (var in = new DeflaterInputStream(new ByteArrayInputStream(s.getBytes(UTF_8)))) {
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static HttpHeaders headers(String... pairs) {
    var headers = new LinkedHashMap<String, List<String>>();
    for (int i = 0, len = pairs.length; i < len; i += 2) {
      headers.computeIfAbsent(pairs[i], __ -> new ArrayList<>()).add(pairs[i + 1]);
    }
    return HttpHeaders.of(headers, (n, v) -> true);
  }

  @CanIgnoreReturnValue
  public static int copyRemaining(ByteBuffer src, ByteBuffer dst) {
    int toCopy = Math.min(src.remaining(), dst.remaining());
    int srcLimit = src.limit();
    src.limit(src.position() + toCopy);
    dst.put(src);
    src.limit(srcLimit);
    return toCopy;
  }

  public static byte[] load(Class<?> caller, String location) {
    var in = caller.getResourceAsStream(location);
    if (in == null) {
      throw new AssertionError("couldn't find resource: " + location);
    }
    try (in) {
      return in.readAllBytes();
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }
  }

  public static String loadUtf8(Class<?> caller, String location) {
    return UTF_8.decode(ByteBuffer.wrap(load(caller, location))).toString();
  }

  public static List<String> lines(String s) {
    return s.lines().collect(Collectors.toUnmodifiableList());
  }

  public static List<Path> listFiles(Path dir) throws IOException {
    try (var stream = Files.list(dir)) {
      return stream.collect(Collectors.toUnmodifiableList());
    }
  }

  public static byte[] toByteArray(ByteBuffer buffer) {
    var bytes = new byte[buffer.remaining()];
    buffer.get(bytes);
    return bytes;
  }

  public static X509Certificate localhostCert() {
    return new HeldCertificate.Builder()
        .addSubjectAlternativeName(InetAddress.getLoopbackAddress().getCanonicalHostName())
        .build()
        .certificate();
  }

  /** Build {@code SSLContext} that trusts a self-assigned certificate for the loopback address. */
  public static SSLContext localhostSslContext() {
    var heldCertificate =
        new HeldCertificate.Builder()
            .addSubjectAlternativeName(InetAddress.getLoopbackAddress().getCanonicalHostName())
            .build();
    return new HandshakeCertificates.Builder()
        .heldCertificate(heldCertificate)
        .addTrustedCertificate(heldCertificate.certificate())
        .build()
        .sslContext();
  }

  public static BufferedReader inputReaderOf(Process process) {
    return new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
  }

  public static <T> HttpResponse<T> okResponseOf(
      HttpRequest request, ByteBuffer responseBody, BodyHandler<T> bodyHandler) {
    return okResponseOf(request, decodeBody(responseBody, bodyHandler));
  }

  public static <T> HttpResponse<T> okResponseOf(HttpRequest request, T body) {
    return ResponseBuilder.create()
        .statusCode(HTTP_OK)
        .request(request)
        .uri(request.uri())
        .version(request.version().orElse(HttpClient.Version.HTTP_1_1))
        .body(body)
        .build();
  }

  private static <T> T decodeBody(ByteBuffer responseBody, BodyHandler<T> bodyHandler) {
    var subscriber =
        bodyHandler.apply(
            new ImmutableResponseInfo(HTTP_OK, headers(), HttpClient.Version.HTTP_1_1));
    subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    if (responseBody.hasRemaining()) {
      subscriber.onNext(List.of(responseBody));
    }
    subscriber.onComplete();
    try {
      return subscriber.getBody().toCompletableFuture().get();
    } catch (InterruptedException e) {
      throw new CompletionException(e);
    } catch (ExecutionException e) {
      throw new CompletionException(e.getCause());
    }
  }
}
