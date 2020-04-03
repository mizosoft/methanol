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

package com.github.mizosoft.methanol.blackbox;

import static com.github.mizosoft.methanol.MoreBodyHandlers.decoding;
import static com.github.mizosoft.methanol.MoreBodyHandlers.fromAsyncSubscriber;
import static com.github.mizosoft.methanol.MoreBodyHandlers.ofByteChannel;
import static com.github.mizosoft.methanol.MoreBodyHandlers.ofDeferredObject;
import static com.github.mizosoft.methanol.MoreBodyHandlers.ofObject;
import static com.github.mizosoft.methanol.MoreBodyHandlers.ofReader;
import static com.github.mizosoft.methanol.MoreBodyHandlers.withReadTimeout;
import static com.github.mizosoft.methanol.MoreBodyPublishers.ofMediaType;
import static com.github.mizosoft.methanol.testutils.TestUtils.headers;
import static com.github.mizosoft.methanol.testutils.TestUtils.lines;
import static com.github.mizosoft.methanol.testutils.TestUtils.load;
import static com.github.mizosoft.methanol.testutils.TestUtils.loadAscii;
import static java.net.http.HttpRequest.BodyPublishers.fromPublisher;
import static java.net.http.HttpResponse.BodyHandlers.discarding;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.HttpReadTimeoutException;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MoreBodyPublishers;
import com.github.mizosoft.methanol.MultipartBodyPublisher;
import com.github.mizosoft.methanol.MultipartBodyPublisher.Part;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.blackbox.Bruh.BruhMoment;
import com.github.mizosoft.methanol.blackbox.Bruh.BruhMoments;
import com.github.mizosoft.methanol.testutils.BuffIterator;
import com.github.mizosoft.methanol.testutils.MockGzipMember;
import com.github.mizosoft.methanol.testutils.MockGzipMember.CorruptionMode;
import com.github.mizosoft.methanol.testutils.RegistryFileTypeDetector;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Filter;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;
import okhttp3.mockwebserver.MockResponse;
import okio.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.example.unicast.AsyncIterablePublisher;

class IntegrationTests extends Lifecycle {

  private static final Base64.Decoder BASE64_DEC = Base64.getDecoder();

  private static final String poem =
        "Roses are red,\n"
      + "Violets are blue,\n"
      + "I hope my tests pass\n"
      + "I really hope they do";
  private static final String lotsOfText;
  private static final Map<String, String> poemEncodings;
  private static final Map<String, byte[]> lotsOfTextEncodings;
  private static final String lotsOfJson;
  private static final List<Map<String, Object>> lotsOfJsonDecoded;
  private static final BruhMoments bruhMoments;
  static {
    Class<?> cls = IntegrationTests.class;

    lotsOfText = loadAscii(cls, "/payload/alice.txt");
    lotsOfJson = loadAscii(cls, "/payload/lots_of_json.json");

    poemEncodings = Map.of(
        "gzip", "H4sIAAAAAAAAAAvKL04tVkgsSlUoSk3R4QrLzM9JLYEIJOWUpupweSpk5BekKuRWKpSkFgNlChKLi4GCRamJOTmVELmSjNRKhZR8APk5jwRLAAAA",
        "deflate", "eJwLyi9OLVZILEpVKEpN0eEKy8zPSS2BCCTllKbqcHkqZOQXpCrkViqUpBYDZQoSi4uBgkWpiTk5lRC5kozUSoWUfADV9hoM",
        "br", "G0oAgIyUq+1omSRlpW7rK2n013L4gqBcPovgkAP2L7As4eaBQLABJ9oyDOFfbZk54qgCr956gL4JUBefr6J90wYuSdm+PwU=",
        "badzip", "");

    lotsOfTextEncodings = Map.of(
        "gzip", load(cls, "/payload/alice.gz"),
        "deflate", load(cls, "/payload/alice.zz"),
        "br", load(cls, "/payload/alice.br"),
        "badzip", new byte[0]);

    var mapper = new JsonMapper();
    var type = new TypeRef<List<Map<String, Object>>>() {};
    try {
      lotsOfJsonDecoded = mapper.readerFor(mapper.constructType(type.type()))
          .readValue(lotsOfJson);
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }

    bruhMoments = BruhMoments.newBuilder()
        .addMoments(0, BruhMoment.newBuilder().setMessage("bruh"))
        .addMoments(1, BruhMoment.newBuilder().setMessage("bbruuuhhh"))
        .addMoments(2, BruhMoment.newBuilder().setMessage("bbrrruuuuuuuhhhhhhhh!!??"))
        .build();
  }

  private static final String SERVICE_LOGGER_NAME =
      "com.github.mizosoft.methanol.internal.spi.ServiceCache";

  private static Filter originalLogFilter;

  @BeforeAll
  static void turnOffServiceLogger() {
    // Do not log service loader failures.
    Logger logger = Logger.getLogger(SERVICE_LOGGER_NAME);
    System.out.println("logger: " + logger);
    System.out.println("logger level: " + logger.getLevel());
    System.out.println("logger filter: " + logger.getFilter());
    originalLogFilter = logger.getFilter();
    logger.setFilter(l -> false);
  }

  @AfterAll
  static void resetServiceLogger() {
    Logger logger = Logger.getLogger(SERVICE_LOGGER_NAME);
    System.out.println("logger: " + logger);
    System.out.println("logger level: " + logger.getLevel());
    System.out.println("logger filter: " + logger.getFilter());
    logger.setFilter(originalLogFilter);
  }

  private void assertDecodesSmall(String encoding) throws Exception {
    server.enqueue(new MockResponse()
        .setBody(okBuffer(BASE64_DEC.decode(poemEncodings.get(encoding))))
        .setHeader("Content-Encoding", encoding));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, decoding(ofString()));
    assertEquals(poem, response.body());
  }

  private void assertDecodesLarge(String encoding) throws Exception {
    server.enqueue(new MockResponse()
        .setBody(okBuffer(lotsOfTextEncodings.get(encoding)))
        .setHeader("Content-Encoding", encoding));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, decoding(ofString()));
    assertLinesMatch(lines(lotsOfText), lines(response.body()));
  }

  @Test
  void decoding_gzip() throws Exception {
    assertDecodesSmall("gzip");
    assertDecodesLarge("gzip");
  }

  @Test
  void decoding_deflate() throws Exception {
    assertDecodesSmall("deflate");
    assertDecodesLarge("deflate");
  }

  @Test
  void decoding_brotli() throws Exception {
    assumeTrue(BodyDecoder.Factory.getFactory("br").isPresent());
    assertDecodesSmall("br");
    assertDecodesLarge("br");
  }

  @Test
  void decoding_concatenatedGzip() throws Exception {
    var firstMember = lotsOfTextEncodings.get("gzip");
    var secondMember = BASE64_DEC.decode(poemEncodings.get("gzip"));
    var thirdMember = MockGzipMember.newBuilder()
        .addComment(55)
        .addFileName(555)
        .addExtraField(5555)
        .addHeaderChecksum()
        .setText()
        .data(lotsOfText.getBytes(US_ASCII))
        .build()
        .getBytes();
    var buffer = new Buffer()
        .write(firstMember)
        .write(secondMember)
        .write(thirdMember);
    server.enqueue(new MockResponse()
        .setBody(buffer)
        .setHeader("Content-Encoding", "gzip"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, decoding(ofString()));
    assertLinesMatch(lines(lotsOfText + poem + lotsOfText), lines(response.body()));
  }

  @Test
  void decoding_corruptConcatenatedGzip() {
    var firstMember = lotsOfTextEncodings.get("gzip");
    var secondMember = MockGzipMember.newBuilder()
        .data(poem.getBytes(US_ASCII))
        .corrupt(CorruptionMode.FLG, 0xE0) // add reserved flag
        .build()
        .getBytes();
    var buffer = new Buffer()
        .write(firstMember)
        .write(secondMember);
    server.enqueue(new MockResponse()
        .setHeader("Content-Encoding", "gzip")
        .setBody(buffer));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var ex = assertThrows(IOException.class, () -> client.send(request, decoding(ofString())));
    // If a concat member has a corrupt header, it is considered trailing garbage (end of gzip stream)
    assertEquals("gzip stream finished prematurely", ex.getMessage());
  }

  @Test
  void decoding_badZip() {
    assertThrows(IOException.class, () -> assertDecodesSmall("badzip"));
  }

  @Test
  void decoding_unsupported() {
    server.enqueue(new MockResponse().setHeader("Content-Encoding", "alienzip"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var ioe = assertThrows(IOException.class, () -> client.send(request, decoding(ofString())));
    assertTrue(ioe.getCause() instanceof UnsupportedOperationException, ioe.toString());
  }

  @Test
  void decoding_nestedHandlerGetsNoLengthOrEncoding() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(okBuffer(BASE64_DEC.decode(poemEncodings.get("gzip"))))
        .setHeader("Content-Encoding", "gzip"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var headers = new AtomicReference<HttpHeaders>();
    var response = client.send(request, decoding(info -> {
      headers.set(info.headers());
      return BodyHandlers.ofString().apply(info);
    }));
    assertEquals(poem, response.body());
    var headersMap = headers.get().map();
    assertFalse(headersMap.containsKey("Content-Encoding"));
    assertFalse(headersMap.containsKey("Content-Length"));
  }

  @Test
  void decoding_noEncoding() throws Exception {
    server.enqueue(new MockResponse().setBody(poem));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, decoding(ofString()));
    assertEquals(poem, response.body());
  }

  @Test
  void ofByteChannel_throttledGzippedWithExecutor() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(okBuffer(lotsOfTextEncodings.get("gzip")))
        .setHeader("Content-Encoding", "gzip")
        .throttleBody(16 * 1024, 100, TimeUnit.MILLISECONDS)); // 100 MS every 16 KB
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, decoding(ofByteChannel(), executor));
    try (var responseReader = new BufferedReader(Channels.newReader(response.body(), US_ASCII))) {
      var expectedReader = new BufferedReader(new StringReader(lotsOfText));
      String expectedLine;
      while ((expectedLine = expectedReader.readLine()) != null) {
        assertEquals(expectedLine, responseReader.readLine());
      }
      assertNull(responseReader.readLine());
    }
  }

  @Test
  @Disabled // MockWebServer never interrupts throttled body in shutdown
  void ofByteChannel_interruptThrottled() {
    var amountToRead = 128;
    server.enqueue(new MockResponse()
        .setBody(lotsOfText)
        .throttleBody(amountToRead, Long.MAX_VALUE, TimeUnit.MILLISECONDS));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var throwableFuture = new CompletableFuture<Throwable>();
    client.sendAsync(request, ofByteChannel())
        .thenAccept(res -> {
          try {
            var channel = res.body();
            var buff = ByteBuffer.allocate(amountToRead);
            // this loop should execute once
            while (channel.read(buff.rewind()) != -1) {
              var readerThread = Thread.currentThread();
              new Thread(readerThread::interrupt).start();
            }
          } catch (Throwable t) {
            throwableFuture.complete(t);
          }
        });
    var throwable = throwableFuture.join();
    assertTrue(throwable instanceof ClosedByInterruptException, String.valueOf(throwable));
  }

  @Test
  void ofReader_gzipped() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(okBuffer(lotsOfTextEncodings.get("gzip")))
        .setHeader("Content-Encoding", "gzip"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, decoding(ofReader()));
    try (var responseReader = new BufferedReader(response.body())) {
      var expectedReader = new BufferedReader(new StringReader(lotsOfText));
      String expectedLine;
      while ((expectedLine = expectedReader.readLine()) != null) {
        assertEquals(expectedLine, responseReader.readLine());
      }
      assertNull(responseReader.readLine());
    }
  }

  @Test
  void ofObject_protobuf() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(okBuffer(bruhMoments.toByteArray()))
        .addHeader("Content-Type", "application/x-protobuf"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, ofObject(BruhMoments.class));
    assertEquals(bruhMoments, response.body());
  }

  @Test
  void ofObject_gzippedDeferredProtobuf() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(okBuffer(gzip(bruhMoments.toByteArray())))
        .addHeader("Content-Encoding", "gzip")
        .addHeader("Content-Type", "application/octet-stream"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, decoding(ofDeferredObject(BruhMoments.class)));
    assertEquals(bruhMoments, response.body().get());
  }

  @Test
  void ofObject_gzippedJsonWithExecutor() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(okBuffer(gzip(lotsOfJson.getBytes(US_ASCII))))
        .addHeader("Content-Encoding", "gzip")
        .addHeader("Content-Type", "application/json"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var type = new TypeRef<List<Map<String, Object>>>() {};
    var response = client.send(request, decoding(ofObject(type), executor));
    assertEquals(lotsOfJsonDecoded, response.body());
  }

  @Test
  void ofObject_deferredJson() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(lotsOfJson)
        .addHeader("Content-Type", "application/json"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var type = new TypeRef<List<Map<String, Object>>>() {};
    var response = client.send(request, ofObject(type));
    assertEquals(lotsOfJsonDecoded, response.body());
  }

  @Test
  void ofObject_unsupported() {
    server.enqueue(new MockResponse()
        .setBody("heuhuehue")
        .setHeader("Content-Type", "application/x-bruh"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var ioe = assertThrows(IOException.class, () -> client.send(request, ofObject(String.class)));
    assertTrue(ioe.getCause() instanceof UnsupportedOperationException, ioe.toString());
  }

  @Test
  void ofObject_stringDecoder() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(poem)
        .addHeader("Content-Type", "text/plain"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, ofObject(String.class));
    assertEquals(poem, response.body());
  }

  @Test
  void fromAsyncSubscriber_uncompletedToCompletedBody() throws Exception {
    server.enqueue(new MockResponse()
        .setBody(okBuffer(new byte[] {1})));
    var request = HttpRequest.newBuilder(server.url("/").uri())
        .timeout(Duration.ofSeconds(10))
        .build();
    var deadlockBody = new BodySubscriber<>() {
      private final CompletableFuture<Subscription> subscriptionCf = new CompletableFuture<>();

      @Override public CompletionStage<Object> getBody() {
        return new CompletableFuture<>(); // never completes!
      }
      @Override public void onSubscribe(Subscription subscription) {
        subscriptionCf.complete(subscription);
      }
      @Override public void onNext(List<ByteBuffer> item) {}
      @Override public void onError(Throwable throwable) {}
      @Override public void onComplete() {}
    };
    var response = client.send(request, fromAsyncSubscriber(deadlockBody, body -> body.subscriptionCf));
    response.body().cancel(); // this closes the connection
  }

  @Test
  void withReadTimeout_readThroughByteChannel() throws Exception {
    var timeoutMillis = 50L;
    server.enqueue(new MockResponse()
        .setBody(poem)
        .throttleBody(0, timeoutMillis * 10, TimeUnit.MILLISECONDS));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(
        request, withReadTimeout(ofByteChannel(), Duration.ofMillis(timeoutMillis)));
    try (var channel = response.body()) {
      assertReadTimeout(channel);
    }
  }

  @Test
  void withReadTimeout_readThroughByteChannel_customScheduler() throws Exception {
    var scheduler = Executors.newScheduledThreadPool(1);
    try {
      var timeoutMillis = 50L;
      server.enqueue(new MockResponse()
          .setBody(poemEncodings.get("gzip"))
          .setHeader("Content-Encoding", "gzip")
          .throttleBody(0, timeoutMillis * 10, TimeUnit.MILLISECONDS));
      var request = HttpRequest.newBuilder(server.url("/").uri()).build();
      var response = client.send(
          request,
          withReadTimeout(
              decoding(ofByteChannel(), executor), Duration.ofMillis(timeoutMillis), scheduler));
      try (var channel = response.body()) {
        assertReadTimeout(channel);
      }
    } finally {
      scheduler.shutdown();
    }
  }

  @Test
  void sendFormBody(@TempDir Path tempDir) throws Exception {
    var tweet = new Tweet();
    tweet.sender = "Albert Einstein";
    tweet.content = "Is math related to science?";
    var theoryFile = Files.createFile(tempDir.resolve("relativity.theory"));
    Files.writeString(theoryFile, "Time is relative bro");
    RegistryFileTypeDetector.register("theory", MediaType.parse("application/x-theory"));
    var multipartBody = MultipartBodyPublisher.newBuilder()
        .boundary("my_awesome_boundary")
        .textPart("action", "sendTweet")
        .formPart("tweet", MoreBodyPublishers.ofObject(tweet, MediaType.of("application", "json")))
        .filePart("attachment", theoryFile)
        .build();
    var request = HttpRequest.newBuilder(server.url("/").uri())
        .header("Content-Type", multipartBody.mediaType().toString())
        .POST(multipartBody)
        .build();
    client.sendAsync(request, discarding());
    var sentRequest = server.takeRequest();
    assertEquals("multipart/form-data; boundary=my_awesome_boundary",
        sentRequest.getHeader("Content-Type"));
    var expectedBody =
              "--my_awesome_boundary\r\n"
            + "Content-Disposition: form-data; name=\"action\"\r\n"
            + "Content-Type: text/plain; charset=utf-8\r\n"
            + "\r\n"
            + "sendTweet\r\n"
            + "--my_awesome_boundary\r\n"
            + "Content-Disposition: form-data; name=\"tweet\"\r\n"
            + "Content-Type: application/json\r\n"
            + "\r\n"
            + "{\"sender\":\"Albert Einstein\",\"content\":\"Is math related to science?\"}\r\n"
            + "--my_awesome_boundary\r\n"
            + "Content-Disposition: form-data; name=\"attachment\"; filename=\"relativity.theory\"\r\n"
            + "Content-Type: application/x-theory\r\n"
            + "\r\n"
            + "Time is relative bro\r\n"
            + "--my_awesome_boundary--\r\n";
    assertEquals(expectedBody, sentRequest.getBody().readUtf8());
  }

  @Test
  void sendEmailBody() throws Exception {
    var multipartAlternative = MultipartBodyPublisher.newBuilder()
        .mediaType(MediaType.of("multipart", "alternative"))
        .boundary("my_cool_boundary")
        .part(Part.create(
            headers("Content-Transfer-Encoding", "quoted-printable"),
            MoreBodyPublishers.ofObject("Hey, that's pretty good", MediaType.of("text", "plain"))))
        .part(Part.create(
            headers("Content-Transfer-Encoding", "quoted-printable"),
            MoreBodyPublishers
                .ofObject("<h1>Hey, that's pretty good</h1>", MediaType.of("text", "html"))))
        .build();
    var attachment = load(getClass(), "/payload/alice.txt");
    var attachmentPublisher = FlowAdapters.toFlowPublisher(new AsyncIterablePublisher<>(
        () -> new BuffIterator(ByteBuffer.wrap(attachment), 1024), executor));
    var multipartMixed = MultipartBodyPublisher.newBuilder()
        .mediaType(MediaType.of("multipart", "mixed"))
        .boundary("no_boundary_is_like_my_boundary")
        .part(Part.create(headers(), multipartAlternative))
        .part(Part.create(
            headers("Content-Disposition", "attachment; name=\"file_attachment\"; filename=\"alice.txt\""),
            ofMediaType(
                fromPublisher(attachmentPublisher, attachment.length),
                MediaType.of("text", "plain"))))
        .build();
    var expected_template =
              "--no_boundary_is_like_my_boundary\r\n"
            + "Content-Type: multipart/alternative; boundary=my_cool_boundary\r\n"
            + "\r\n"
            + "--my_cool_boundary\r\n"
            + "Content-Transfer-Encoding: quoted-printable\r\n"
            + "Content-Type: text/plain\r\n"
            + "\r\n"
            + "Hey, that's pretty good\r\n"
            + "--my_cool_boundary\r\n"
            + "Content-Transfer-Encoding: quoted-printable\r\n"
            + "Content-Type: text/html\r\n"
            + "\r\n"
            + "<h1>Hey, that's pretty good</h1>\r\n"
            + "--my_cool_boundary--\r\n"
            + "\r\n"
            + "--no_boundary_is_like_my_boundary\r\n"
            + "Content-Disposition: attachment; name=\"file_attachment\"; filename=\"alice.txt\"\r\n"
            + "Content-Type: text/plain\r\n"
            + "\r\n"
            + "%s\r\n" // alice goes here
            + "--no_boundary_is_like_my_boundary--\r\n";
    var request = HttpRequest.newBuilder(server.url("/").uri())
        .header("Content-Type", multipartMixed.mediaType().toString())
        .POST(multipartMixed)
        .build();
    client.sendAsync(request, discarding());
    var sentRequest = server.takeRequest();
    assertEquals("multipart/mixed; boundary=no_boundary_is_like_my_boundary",
        sentRequest.getHeader("Content-Type"));
    assertEquals(sentRequest.getHeader("Content-Length"),
        Long.toString(multipartMixed.contentLength()));
    assertEquals(
        String.format(expected_template, loadAscii(getClass(), "/payload/alice.txt")),
        sentRequest.getBody().readUtf8());
  }

  private static void assertReadTimeout(ReadableByteChannel channel) {
    var ioe = assertThrows(IOException.class, () -> channel.read(ByteBuffer.allocate(1)));
    assertSame(HttpReadTimeoutException.class, ioe.getCause().getClass());
  }

  private static Buffer okBuffer(byte[] bytes) {
    return new Buffer().write(bytes);
  }

  private static byte[] gzip(byte[] bytes) {
    try {
      var outBuffer = new ByteArrayOutputStream();
      try (var gzOut = new GZIPOutputStream(outBuffer)) {
        gzOut.write(bytes);
      }
      return outBuffer.toByteArray();
    } catch (IOException ioe) {
      throw new AssertionError(ioe);
    }
  }

  static final class Tweet {

    public String sender;
    public String content;

    public Tweet() {}
  }
}
