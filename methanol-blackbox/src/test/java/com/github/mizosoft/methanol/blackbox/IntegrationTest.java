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

package com.github.mizosoft.methanol.blackbox;

import static com.github.mizosoft.methanol.MoreBodyHandlers.decoding;
import static com.github.mizosoft.methanol.MoreBodyHandlers.fromAsyncSubscriber;
import static com.github.mizosoft.methanol.MoreBodyHandlers.ofByteChannel;
import static com.github.mizosoft.methanol.MoreBodyHandlers.ofDeferredObject;
import static com.github.mizosoft.methanol.MoreBodyHandlers.ofObject;
import static com.github.mizosoft.methanol.MoreBodyHandlers.ofReader;
import static com.github.mizosoft.methanol.MoreBodyHandlers.withReadTimeout;
import static com.github.mizosoft.methanol.MoreBodyPublishers.ofMediaType;
import static com.github.mizosoft.methanol.testing.TestUtils.gunzip;
import static com.github.mizosoft.methanol.testing.TestUtils.headers;
import static com.github.mizosoft.methanol.testing.TestUtils.lines;
import static com.github.mizosoft.methanol.testing.TestUtils.load;
import static com.github.mizosoft.methanol.testing.TestUtils.loadUtf8;
import static com.github.mizosoft.methanol.testing.TestUtils.zlibUnwrap;
import static java.net.http.HttpRequest.BodyPublishers.fromPublisher;
import static java.net.http.HttpResponse.BodyHandlers.discarding;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.HttpReadTimeoutException;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MoreBodyPublishers;
import com.github.mizosoft.methanol.MultipartBodyPublisher;
import com.github.mizosoft.methanol.MultipartBodyPublisher.Part;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.TypeRef;
import com.github.mizosoft.methanol.WritableBodyPublisher;
import com.github.mizosoft.methanol.blackbox.PersonOuterClass.People;
import com.github.mizosoft.methanol.blackbox.PersonOuterClass.Person;
import com.github.mizosoft.methanol.blackbox.support.JacksonMapper;
import com.github.mizosoft.methanol.testing.ByteBufferIterator;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.IterablePublisher;
import com.github.mizosoft.methanol.testing.MockGzipStream;
import com.github.mizosoft.methanol.testing.MockGzipStream.CorruptionMode;
import com.github.mizosoft.methanol.testing.RegistryFileTypeDetector;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPOutputStream;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okio.Buffer;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.publisher.Flux;

@ExtendWith(ExecutorExtension.class)
class IntegrationTest {
  private static final Base64.Decoder BASE64_DECODER = Base64.getDecoder();

  private static final String POEM =
      "Roses are red,\n"
          + "Violets are blue,\n"
          + "I hope my tests pass\n"
          + "I really hope they do";

  // There's a slight distinction between how moxy's implementation of JavaEE's JAXB & glassfish's
  // implementation of Jakarta's JAXB generate the XML, particularly in the XML declaration.

  private static final String EPIC_ART_COURSE_JAVAX_XML_UTF_8 =
      "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
          + "<course type=\"ART\">"
          + "<enrolled-students>"
          + "<student>"
          + "<name>Leonardo Da Vinci</name>"
          + "</student>"
          + "<student>"
          + "<name>Michelangelo</name>"
          + "</student>"
          + "</enrolled-students>"
          + "</course>";

  private static final String EPIC_ART_COURSE_JAKARTA_XML_UTF_8 =
      "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
          + "<course type=\"ART\">"
          + "<enrolled-students>"
          + "<student>"
          + "<name>Leonardo Da Vinci</name>"
          + "</student>"
          + "<student>"
          + "<name>Michelangelo</name>"
          + "</student>"
          + "</enrolled-students>"
          + "</course>";

  private static final CourseJavax EPIC_ART_COURSE_JAVAX =
      new CourseJavax(
          Type.ART,
          List.of(new StudentJavax("Leonardo Da Vinci"), new StudentJavax("Michelangelo")));

  private static final CourseJakarta EPIC_ART_COURSE_JAKARTA =
      new CourseJakarta(
          Type.ART,
          List.of(new StudentJakarta("Leonardo Da Vinci"), new StudentJakarta("Michelangelo")));

  private static String lotsOfText;
  private static Map<String, String> poemEncodings;
  private static Map<String, byte[]> lotsOfTextEncodings;
  private static String lotsOfJson;
  private static List<Map<String, Object>> lotsOfJsonDecoded;
  private static People people;

  private MockWebServer server;
  private HttpClient client;
  private Executor executor;
  private ScheduledExecutorService scheduler;

  @BeforeAll
  static void readTestData() {
    var cls = IntegrationTest.class;

    lotsOfText = loadUtf8(cls, "/payload/alice.txt");
    lotsOfJson = loadUtf8(cls, "/payload/lots_of_json.json");

    poemEncodings =
        Map.of(
            "gzip",
                "H4sIAAAAAAAAAAvKL04tVkgsSlUoSk3R4QrLzM9JLYEIJOWUpupweSpk5BekKuRWKpSkFgNlChKLi4GCRamJOTmVELmSjNRKhZR8APk5jwRLAAAA",
            "deflate",
                "eJwLyi9OLVZILEpVKEpN0eEKy8zPSS2BCCTllKbqcHkqZOQXpCrkViqUpBYDZQoSi4uBgkWpiTk5lRC5kozUSoWUfADV9hoM",
            "br",
                "G0oAgIyUq+1omSRlpW7rK2n013L4gqBcPovgkAP2L7As4eaBQLABJ9oyDOFfbZk54qgCr956gL4JUBefr6J90wYuSdm+PwU=",
            "badzip", "");

    lotsOfTextEncodings =
        Map.of(
            "gzip", load(cls, "/payload/alice.gz"),
            "deflate", load(cls, "/payload/alice.zz"),
            "br", load(cls, "/payload/alice.br"),
            "badzip", new byte[0]);

    try {
      var mapper = new JsonMapper();
      lotsOfJsonDecoded =
          mapper
              .readerFor(mapper.constructType(new TypeRef<List<Map<String, Object>>>() {}.type()))
              .readValue(lotsOfJson);
    } catch (IOException ioe) {
      throw new UncheckedIOException(ioe);
    }

    people =
        People.newBuilder()
            .addPeople(0, Person.newBuilder().setName("Jacob"))
            .addPeople(1, Person.newBuilder().setName("Jack"))
            .addPeople(2, Person.newBuilder().setName("John"))
            .build();
  }

  @BeforeAll
  static void registerJaxbImplementation() {
    System.setProperty(
        javax.xml.bind.JAXBContext.JAXB_CONTEXT_FACTORY, MoxyJaxbContextFactory.class.getName());
  }

  @BeforeEach
  void setUpLifecycle() throws IOException {
    server = new MockWebServer();
    server.start();
    client = Methanol.newBuilder().autoAcceptEncoding(false).build();
    executor = Executors.newFixedThreadPool(8);
    scheduler = Executors.newSingleThreadScheduledExecutor();
  }

  @AfterEach
  void tearDownLifecycle() throws IOException {
    server.shutdown();
    TestUtils.shutdown(executor, scheduler);
  }

  private void assertDecodes(String encoding, String expected, byte[] compressed) throws Exception {
    server.enqueue(
        new MockResponse().setBody(okBuffer(compressed)).setHeader("Content-Encoding", encoding));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, decoding(ofString()));
    assertLinesMatch(lines(expected), lines(response.body()));
  }

  private void assertDecodesSmall(String encoding) throws Exception {
    var compressed = BASE64_DECODER.decode(poemEncodings.get(encoding));
    assertDecodes(encoding, POEM, compressed);
    // Test deflate body without zlib wrapping
    if (encoding.equals("deflate")) {
      assertDecodes(encoding, POEM, zlibUnwrap(compressed));
    }
  }

  private void assertDecodesLarge(String encoding) throws Exception {
    var compressed = lotsOfTextEncodings.get(encoding);
    assertDecodes(encoding, lotsOfText, compressed);
    // Test deflate body without zlib wrapping
    if (encoding.equals("deflate")) {
      assertDecodes(encoding, lotsOfText, zlibUnwrap(compressed));
    }
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
    var secondMember = BASE64_DECODER.decode(poemEncodings.get("gzip"));
    var thirdMember =
        MockGzipStream.newBuilder()
            .addComment(55)
            .addFileName(555)
            .addExtraField(5555)
            .addHeaderChecksum()
            .setText()
            .data(lotsOfText.getBytes(US_ASCII))
            .build()
            .toByteArray();
    var buffer = new Buffer().write(firstMember).write(secondMember).write(thirdMember);
    server.enqueue(new MockResponse().setBody(buffer).setHeader("Content-Encoding", "gzip"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, decoding(ofString()));
    assertLinesMatch(lines(lotsOfText + POEM + lotsOfText), lines(response.body()));
  }

  @Test
  void decoding_corruptConcatenatedGzip() {
    var firstMember = lotsOfTextEncodings.get("gzip");
    var secondMember =
        MockGzipStream.newBuilder()
            .data(POEM.getBytes(US_ASCII))
            .corrupt(CorruptionMode.FLG, 0xE0) // add reserved flag
            .build()
            .toByteArray();
    var buffer = new Buffer().write(firstMember).write(secondMember);
    server.enqueue(new MockResponse().setHeader("Content-Encoding", "gzip").setBody(buffer));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var ex = assertThrows(IOException.class, () -> client.send(request, decoding(ofString())));
    // If a concat member has a corrupt header, it is considered trailing garbage (end of gzip
    // stream)
    assertEquals("Gzip stream finished prematurely", ex.getMessage());
    // Will suppress read header failure
    var suppressedIoe = ex.getCause().getSuppressed()[0];
    assertTrue(suppressedIoe instanceof IOException);
    assertEquals("Unsupported flags: 0xe0", suppressedIoe.getMessage());
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
    server.enqueue(
        new MockResponse()
            .setBody(okBuffer(BASE64_DECODER.decode(poemEncodings.get("gzip"))))
            .setHeader("Content-Encoding", "gzip"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var headers = new AtomicReference<HttpHeaders>();
    var response =
        client.send(
            request,
            decoding(
                info -> {
                  headers.set(info.headers());
                  return BodyHandlers.ofString().apply(info);
                }));
    assertEquals(POEM, response.body());
    var headersMap = headers.get().map();
    assertFalse(headersMap.containsKey("Content-Encoding"));
    assertFalse(headersMap.containsKey("Content-Length"));
  }

  @Test
  void decoding_noEncoding() throws Exception {
    server.enqueue(new MockResponse().setBody(POEM));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, decoding(ofString()));
    assertEquals(POEM, response.body());
  }

  @Test
  void ofByteChannel_throttledGzippedWithExecutor() throws Exception {
    server.enqueue(
        new MockResponse()
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
  void ofByteChannel_interruptThrottled() throws Exception {
    var amountToRead = 128;
    server.enqueue(
        new MockResponse()
            .setBody(lotsOfText)
            .throttleBody(amountToRead, Long.MAX_VALUE, TimeUnit.MILLISECONDS));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var throwableFuture = new CompletableFuture<Throwable>();
    client
        .sendAsync(request, ofByteChannel())
        .thenAccept(
            res -> {
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
    var throwable = throwableFuture.get();
    assertTrue(throwable instanceof ClosedByInterruptException, String.valueOf(throwable));
  }

  @Test
  void ofReader_gzipped() throws Exception {
    server.enqueue(
        new MockResponse()
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
    server.enqueue(
        new MockResponse()
            .setBody(okBuffer(people.toByteArray()))
            .addHeader("Content-Type", "application/x-protobuf"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, ofObject(People.class));
    assertEquals(people, response.body());
  }

  @Test
  void ofObject_gzippedDeferredProtobuf() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(okBuffer(gzip(people.toByteArray())))
            .addHeader("Content-Encoding", "gzip")
            .addHeader("Content-Type", "application/octet-stream"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, decoding(ofDeferredObject(People.class)));
    assertEquals(people, response.body().get());
  }

  @Test
  void ofObject_gzippedJsonWithExecutor() throws Exception {
    server.enqueue(
        new MockResponse()
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
    server.enqueue(
        new MockResponse().setBody(lotsOfJson).addHeader("Content-Type", "application/json"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var type = new TypeRef<List<Map<String, Object>>>() {};
    var response = client.send(request, ofObject(type));
    assertEquals(lotsOfJsonDecoded, response.body());
  }

  @Test
  void ofObject_unsupported() {
    server.enqueue(
        new MockResponse()
            .setBody("What's up")
            .setHeader("Content-Type", "application/x-greeting"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var ioe = assertThrows(IOException.class, () -> client.send(request, ofObject(String.class)));
    assertTrue(ioe.getCause() instanceof UnsupportedOperationException, ioe.toString());
  }

  @Test
  void ofObject_stringDecoder() throws Exception {
    server.enqueue(new MockResponse().setBody(POEM).addHeader("Content-Type", "text/plain"));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response = client.send(request, ofObject(String.class));
    assertEquals(POEM, response.body());
  }

  @Test
  void ofObject_jsonFlux() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(okBuffer(gzip(lotsOfJson.getBytes(UTF_8))))
            .addHeader("Content-Encoding", "gzip")
            .addHeader("Content-Type", "application/json")
            .throttleBody(8 * 1024, 100, TimeUnit.MILLISECONDS));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response =
        client.send(request, decoding(ofObject(new TypeRef<Flux<Map<String, Object>>>() {})));
    var listMono = response.body().collectList();
    assertEquals(Optional.of(lotsOfJsonDecoded), listMono.blockOptional());
  }

  @Test
  void ofObject_uploadFlux() throws Exception {
    server.enqueue(new MockResponse());
    var flux = Flux.fromIterable(lotsOfJsonDecoded);
    var request =
        MutableRequest.POST(
            server.url("/").uri(), MoreBodyPublishers.ofObject(flux, MediaType.APPLICATION_JSON));
    client.sendAsync(request, discarding());

    var recordedRequest = server.takeRequest();
    var uploaded = recordedRequest.getBody().readUtf8();
    var expected = JacksonMapper.get().writeValueAsString(lotsOfJsonDecoded);
    assertEquals(expected, uploaded);
  }

  @Test
  void ofObject_downloadXml() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(okBuffer(gzip(EPIC_ART_COURSE_JAVAX_XML_UTF_8.getBytes(UTF_8))))
            .addHeader("Content-Encoding", "gzip")
            .addHeader("Content-Type", "application/xml"));
    var request = MutableRequest.GET(server.url("/").uri());
    var response = client.send(request, decoding(ofObject(new TypeRef<CourseJavax>() {})));
    assertEquals(EPIC_ART_COURSE_JAVAX, response.body());
  }

  @Test
  void ofObject_downloadXmlJakarta() throws Exception {
    server.enqueue(
        new MockResponse()
            .setBody(okBuffer(gzip(EPIC_ART_COURSE_JAKARTA_XML_UTF_8.getBytes(UTF_8))))
            .addHeader("Content-Encoding", "gzip")
            .addHeader("Content-Type", "application/xml"));
    var request = MutableRequest.GET(server.url("/").uri());
    var response = client.send(request, decoding(ofObject(new TypeRef<CourseJakarta>() {})));
    assertEquals(EPIC_ART_COURSE_JAKARTA, response.body());
  }

  @Test
  void ofObject_uploadXml() throws Exception {
    server.enqueue(new MockResponse());
    var request =
        MutableRequest.POST(
            server.url("/").uri(),
            MoreBodyPublishers.ofObject(
                EPIC_ART_COURSE_JAVAX, MediaType.TEXT_XML.withCharset(UTF_8)));
    client.sendAsync(request, discarding());

    var recordedRequest = server.takeRequest();
    var uploaded = recordedRequest.getBody().readUtf8();
    assertEquals(EPIC_ART_COURSE_JAVAX_XML_UTF_8, uploaded);
  }

  @Test
  void ofObject_uploadXmlJakarta() throws Exception {
    server.enqueue(new MockResponse());
    var request =
        MutableRequest.POST(
            server.url("/").uri(),
            MoreBodyPublishers.ofObject(
                EPIC_ART_COURSE_JAKARTA, MediaType.TEXT_XML.withCharset(UTF_8)));
    client.sendAsync(request, discarding());

    var recordedRequest = server.takeRequest();
    var uploaded = recordedRequest.getBody().readUtf8();
    assertEquals(EPIC_ART_COURSE_JAKARTA_XML_UTF_8, uploaded);
  }

  @Test
  void fromAsyncSubscriber_uncompletedToCompletedBody() throws Exception {
    server.enqueue(new MockResponse().setBody(okBuffer(new byte[] {1})));
    var request =
        HttpRequest.newBuilder(server.url("/").uri())
            .timeout(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS))
            .build();
    var deadlockBody =
        new BodySubscriber<>() {
          private final CompletableFuture<Subscription> subscriptionCf = new CompletableFuture<>();

          @Override
          public CompletionStage<Object> getBody() {
            return new CompletableFuture<>(); // never completes!
          }

          @Override
          public void onSubscribe(Subscription subscription) {
            subscriptionCf.complete(subscription);
          }

          @Override
          public void onNext(List<ByteBuffer> item) {}

          @Override
          public void onError(Throwable throwable) {}

          @Override
          public void onComplete() {}
        };
    var response =
        client.send(request, fromAsyncSubscriber(deadlockBody, body -> body.subscriptionCf));
    response.body().cancel(); // this closes the connection
  }

  @Test
  void withReadTimeout_readThroughByteChannel() throws Exception {
    var timeoutMillis = 50L;
    server.enqueue(
        new MockResponse()
            .setBody(POEM)
            .throttleBody(0, timeoutMillis * 10, TimeUnit.MILLISECONDS));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response =
        client.send(request, withReadTimeout(ofByteChannel(), Duration.ofMillis(timeoutMillis)));
    try (var channel = response.body()) {
      assertReadTimeout(channel);
    }
  }

  @Test
  void withReadTimeout_readThroughByteChannel_customScheduler() throws Exception {
    var timeoutMillis = 50L;
    server.enqueue(
        new MockResponse()
            .setBody(poemEncodings.get("gzip"))
            .setHeader("Content-Encoding", "gzip")
            .throttleBody(0, timeoutMillis * 10, TimeUnit.MILLISECONDS));
    var request = HttpRequest.newBuilder(server.url("/").uri()).build();
    var response =
        client.send(
            request,
            withReadTimeout(
                decoding(ofByteChannel(), executor), Duration.ofMillis(timeoutMillis), scheduler));
    try (var channel = response.body()) {
      assertReadTimeout(channel);
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
    var multipartBody =
        MultipartBodyPublisher.newBuilder()
            .boundary("my_awesome_boundary")
            .textPart("action", "sendTweet")
            .formPart(
                "tweet", MoreBodyPublishers.ofObject(tweet, MediaType.of("application", "json")))
            .filePart("attachment", theoryFile)
            .build();
    var request =
        HttpRequest.newBuilder(server.url("/").uri())
            .header("Content-Type", multipartBody.mediaType().toString())
            .POST(multipartBody)
            .build();
    client.sendAsync(request, discarding());
    var sentRequest = server.takeRequest();
    assertEquals(
        "multipart/form-data; boundary=my_awesome_boundary", sentRequest.getHeader("Content-Type"));
    var expectedBody =
        "--my_awesome_boundary\r\n"
            + "Content-Disposition: form-data; name=\"action\"\r\n"
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
    var multipartAlternative =
        MultipartBodyPublisher.newBuilder()
            .mediaType(MediaType.of("multipart", "alternative"))
            .boundary("my_cool_boundary")
            .part(
                Part.create(
                    headers("Content-Transfer-Encoding", "quoted-printable"),
                    MoreBodyPublishers.ofObject(
                        "Hey, that's pretty good", MediaType.of("text", "plain"))))
            .part(
                Part.create(
                    headers("Content-Transfer-Encoding", "quoted-printable"),
                    MoreBodyPublishers.ofObject(
                        "<h1>Hey, that's pretty good</h1>", MediaType.of("text", "html"))))
            .build();
    var attachment = load(getClass(), "/payload/alice.txt");
    var attachmentPublisher =
        new IterablePublisher<>(
            () -> new ByteBufferIterator(ByteBuffer.wrap(attachment), 1024), executor);
    var multipartMixed =
        MultipartBodyPublisher.newBuilder()
            .mediaType(MediaType.of("multipart", "mixed"))
            .boundary("no_boundary_is_like_my_boundary")
            .part(Part.create(headers(), multipartAlternative))
            .part(
                Part.create(
                    headers(
                        "Content-Disposition",
                        "attachment; name=\"file_attachment\"; filename=\"alice.txt\""),
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
    var request =
        HttpRequest.newBuilder(server.url("/").uri())
            .header("Content-Type", multipartMixed.mediaType().toString())
            .POST(multipartMixed)
            .build();
    client.sendAsync(request, discarding());
    var sentRequest = server.takeRequest();
    assertEquals(
        "multipart/mixed; boundary=no_boundary_is_like_my_boundary",
        sentRequest.getHeader("Content-Type"));
    assertEquals(
        sentRequest.getHeader("Content-Length"), Long.toString(multipartMixed.contentLength()));
    assertEquals(
        String.format(expected_template, loadUtf8(getClass(), "/payload/alice.txt")),
        sentRequest.getBody().readUtf8());
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void sendGzippedRequest(Executor executor) throws Exception {
    var body = WritableBodyPublisher.create();
    var request = MutableRequest.POST(server.url("/").uri(), body);
    client.sendAsync(request, BodyHandlers.discarding());
    CompletableFuture.runAsync(
        () -> {
          try (var gzipOut = new GZIPOutputStream(body.outputStream())) {
            new ByteArrayInputStream(lotsOfText.getBytes(UTF_8)).transferTo(gzipOut);
          } catch (IOException e) {
            fail(e);
          }
        },
        executor);

    var recordedRequest = server.takeRequest();
    assertEquals(lotsOfText, new String(gunzip(recordedRequest.getBody().readByteArray()), UTF_8));
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

  @jakarta.xml.bind.annotation.XmlRootElement(name = "course")
  private static final class CourseJakarta {
    @jakarta.xml.bind.annotation.XmlAttribute(required = true)
    private final Type type;

    @jakarta.xml.bind.annotation.XmlElementWrapper(name = "enrolled-students")
    @jakarta.xml.bind.annotation.XmlElement(name = "student")
    private final List<StudentJakarta> enrolledStudents;

    CourseJakarta() {
      this(Type.UNKNOWN, new ArrayList<>());
    }

    CourseJakarta(Type type, List<StudentJakarta> enrolledStudents) {
      this.type = type;
      this.enrolledStudents = enrolledStudents;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (!(obj instanceof CourseJakarta)) {
        return false;
      }

      var other = (CourseJakarta) obj;
      return type == other.type && enrolledStudents.equals(other.enrolledStudents);
    }

    @Override
    public String toString() {
      return "CourseJakarta[type=" + type + ", enrolledStudents=" + enrolledStudents + "]";
    }
  }

  private static final class StudentJakarta {
    @jakarta.xml.bind.annotation.XmlElement(required = true)
    private final String name;

    StudentJakarta() {
      this("");
    }

    StudentJakarta(String name) {
      this.name = name;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (!(obj instanceof StudentJakarta)) {
        return false;
      }
      return name.equals(((StudentJakarta) obj).name);
    }

    @Override
    public String toString() {
      return "StudentJakarta[name=" + name + "]";
    }
  }

  @javax.xml.bind.annotation.XmlRootElement(name = "course")
  private static final class CourseJavax {
    @javax.xml.bind.annotation.XmlAttribute(required = true)
    private final Type type;

    @javax.xml.bind.annotation.XmlElementWrapper(name = "enrolled-students")
    @javax.xml.bind.annotation.XmlElement(name = "student")
    private final List<StudentJavax> enrolledStudents;

    CourseJavax() {
      this(Type.UNKNOWN, new ArrayList<>());
    }

    CourseJavax(Type type, List<StudentJavax> enrolledStudents) {
      this.type = type;
      this.enrolledStudents = enrolledStudents;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (!(obj instanceof CourseJavax)) {
        return false;
      }

      var other = (CourseJavax) obj;
      return type == other.type && enrolledStudents.equals(other.enrolledStudents);
    }

    @Override
    public String toString() {
      return "Course[type=" + type + ", enrolledStudents=" + enrolledStudents + "]";
    }
  }

  private static final class StudentJavax {
    @javax.xml.bind.annotation.XmlElement(required = true)
    private final String name;

    StudentJavax() {
      this("");
    }

    StudentJavax(String name) {
      this.name = name;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
      if (!(obj instanceof StudentJavax)) {
        return false;
      }
      return name.equals(((StudentJavax) obj).name);
    }

    @Override
    public String toString() {
      return "Student[name=" + name + "]";
    }
  }

  private enum Type {
    UNKNOWN,
    ART,
    CALCULUS
  }

  public static final class MoxyJaxbContextFactory implements javax.xml.bind.JAXBContextFactory {
    public MoxyJaxbContextFactory() {}

    @Override
    public javax.xml.bind.JAXBContext createContext(
        Class<?>[] classesToBeBound, Map<String, ?> properties)
        throws javax.xml.bind.JAXBException {
      return org.eclipse.persistence.jaxb.JAXBContextFactory.createContext(
          classesToBeBound, properties);
    }

    @Override
    public javax.xml.bind.JAXBContext createContext(
        String contextPath, ClassLoader classLoader, Map<String, ?> properties)
        throws javax.xml.bind.JAXBException {
      return org.eclipse.persistence.jaxb.JAXBContextFactory.createContext(
          contextPath, classLoader, properties);
    }
  }
}
