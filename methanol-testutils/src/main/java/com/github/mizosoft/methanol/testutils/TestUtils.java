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

package com.github.mizosoft.methanol.testutils;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.http.HttpHeaders;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Flow.Subscription;
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
  public static final Subscription NOOP_SUBSCRIPTION =
      new Subscription() {
        @Override
        public void request(long n) {}

        @Override
        public void cancel() {}
      };

  public static void awaitUninterruptibly(CountDownLatch latch) {
    while (true) {
      try {
        latch.await();
        return;
      } catch (InterruptedException ignored) {
        // continue;
      }
    }
  }

  public static void awaitUninterruptibly(CyclicBarrier barier) {
    while (true) {
      try {
        barier.await();
        return;
      } catch (InterruptedException ignored) {
        // continue;
      } catch (BrokenBarrierException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static void shutdown(Executor... executors) {
    for (Executor e : executors) {
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

  public static byte[] inflateNowrap(byte[] data) {
    return inflate0(data, new Inflater(true));
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

  public static String loadAscii(Class<?> caller, String location) {
    return US_ASCII.decode(ByteBuffer.wrap(load(caller, location))).toString();
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

  /** Build {@code SSLContext} that trusts a self-assigned certificate for localhost in tests. */
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
}
