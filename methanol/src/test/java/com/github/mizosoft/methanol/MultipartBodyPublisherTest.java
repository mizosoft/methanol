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

import static com.github.mizosoft.methanol.MoreBodyPublishers.ofMediaType;
import static com.github.mizosoft.methanol.MultipartBodyPublisher.newBuilder;
import static com.github.mizosoft.methanol.testutils.TestUtils.headers;
import static java.net.http.HttpRequest.BodyPublishers.fromPublisher;
import static java.net.http.HttpRequest.BodyPublishers.ofInputStream;
import static java.net.http.HttpRequest.BodyPublishers.ofString;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.MultipartBodyPublisher.Part;
import com.github.mizosoft.methanol.testutils.BodyCollector;
import com.github.mizosoft.methanol.testutils.FailedPublisher;
import com.github.mizosoft.methanol.testutils.RegistryFileTypeDetector;
import com.github.mizosoft.methanol.testutils.TestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.example.unicast.AsyncIterablePublisher;

class MultipartBodyPublisherTest {

  @Test
  void defaultMediaType() {
    var type = onePart().build().mediaType();
    assertEquals("multipart", type.type());
    assertEquals("form-data", type.subtype());
  }

  @Test
  void customSubtype() {
    var type = onePart()
        .mediaType(MediaType.parse("multipart/mixed"))
        .build()
        .mediaType();
    assertEquals("mixed", type.subtype());
  }

  @Test
  void customBoundary() {
    var builder = onePart();
    assertEquals("cool_boundary", boundaryOf(builder.boundary("cool_boundary")));
    var customType = MediaType.parse("multipart/alternative")
        .withParameter("boundary", "cool_boundary");
    assertEquals("cool_boundary", boundaryOf(builder.mediaType(customType)));
    assertEquals("cooler_boundary",
        boundaryOf(builder.mediaType(customType).boundary("cooler_boundary")));
  }

  @Test
  void invalidBoundary() {
    var customType = MediaType.parse("multipart/alternative");
    List.<Consumer<String>>of(
        b -> onePart().boundary(b),
        b -> onePart().mediaType(customType.withParameter("boundary", b))
    ).forEach(s -> {
      assertIllegalArg(() -> s.accept("i||ega|_boundary"));
      assertIllegalArg(() -> s.accept("b".repeat(71)));
      assertIllegalArg(() -> s.accept(""));
      assertIllegalArg(() -> s.accept("ends_with_space "));
    });
  }

  @Test
  void invalidMediaType() {
    assertIllegalArg(() -> onePart().mediaType(MediaType.parse("text/plain")));
  }

  @Test
  void contentTypeHeaderWithMimeBodyPublisher() {
    var headers = headers("Content-Type", "application/octet-stream");
    var publisher = ofMediaType(ofString("something"), MediaType.parse("text/plain"));
    assertIllegalArg(() -> Part.create(headers, publisher));
  }

  @Test
  void invalidHeaderName() {
    var headers = headers("Illeg@l-Token", "whatever");
    var publisher = ofString("ligma");
    assertIllegalArg(() -> Part.create(headers, publisher));
  }

  @Test
  void addNoParts() {
    assertThrows(IllegalStateException.class, () -> newBuilder().build());
  }

  @Test
  void serializeBodyPart() {
    var expected =
          "--cool_boundary\r\n"
        + "Foo: bar\r\n"
        + "\r\n"
        + "some content\r\n"
        + "--cool_boundary--\r\n";
    var body = newBuilder()
        .boundary("cool_boundary")
        .part(Part.create(headers("Foo", "bar"), ofString("some content")))
        .build();
    assertContentEquals(expected, body, US_ASCII);
  }

  @Test
  void serializeFormParts() {
    var expected =
          "--cool_boundary\r\n"
        + "Content-Disposition: form-data; name=\"innocent_field\"\r\n"
        + "\r\n"
        + "Hold my innocent cup of water\r\n"
        + "--cool_boundary\r\n"
        + "Content-Disposition: form-data; name=\"hacker_field\"; filename=\"hack.cpp\"\r\n"
        + "Content-Type: text/x-c; charset=ascii\r\n"
        + "\r\n"
        + "system(\"hack pc\");\r\n"
        + "--cool_boundary--\r\n";
    var body = newBuilder()
        .boundary("cool_boundary")
        .formPart("innocent_field", ofString("Hold my innocent cup of water"))
        .formPart("hacker_field", "hack.cpp", ofMediaType(ofString("system(\"hack pc\");"),
            MediaType.parse("text/x-c; charset=ascii")))
        .build();
    assertContentEquals(expected, body, US_ASCII);
  }

  @Test
  void serializeTextPartUtf8() {
    var expected =
          "--cool_boundary\r\n"
        + "Content-Disposition: form-data; name=\"important_question\"\r\n"
        + "\r\n"
        + "Is math related to science? £_£\r\n"
        + "--cool_boundary\r\n"
        + "Content-Disposition: form-data; name=\"less_important_question\"\r\n"
        + "\r\n"
        + "Is earth flat? $_$\r\n"
        + "--cool_boundary--\r\n";
    var body = newBuilder()
        .boundary("cool_boundary")
        .textPart("important_question", "Is math related to science? £_£")
        .textPart("less_important_question", "Is earth flat? $_$", US_ASCII)
        .build();
    assertContentEquals(expected, body, UTF_8);
  }

  @Test
  void serializeFileParts(@TempDir Path tempDir) throws IOException  {
    var crazyFile = Files.createFile(tempDir.resolve("crazy_file.impossible.to.be.detected.by.anything.else"));
    var normalFile = Files.createFile(tempDir.resolve("normal_file.txt"));
    RegistryFileTypeDetector.register(
        "impossible.to.be.detected.by.anything.else", MediaType.parse("application/x-bruh"));
    Files.writeString(crazyFile, "ey yo i'm trippin");
    Files.writeString(normalFile, "we live in a society");
    var expected =
          "--cool_boundary\r\n"
        + "Content-Disposition: form-data; name=\"crazy_file_field\"; filename=\"crazy_file.impossible.to.be.detected.by.anything.else\"\r\n"
        + "Content-Type: application/x-bruh\r\n"
        + "\r\n"
        + "ey yo i'm trippin\r\n"
        + "--cool_boundary\r\n"
        + "Content-Disposition: form-data; name=\"normal_file_field\"; filename=\"normal_file.txt\"\r\n"
        + "Content-Type: text/plain\r\n"
        + "\r\n"
        + "we live in a society\r\n"
        + "--cool_boundary--\r\n";
    var body = newBuilder()
        .boundary("cool_boundary")
        .filePart("crazy_file_field", crazyFile)
        .filePart("normal_file_field", normalFile, MediaType.parse("text/plain"))
        .build();
    assertContentEquals(expected, body, US_ASCII);
  }

  @Test
  void asyncBodyPart() {
    var token = "REPEAT ME!\r\n";
    var count = 10000;
    var expected =
          "--cool_boundary\r\n"
        + "Content-Disposition: form-data; name=\"sync_field_1\"\r\n"
        + "\r\n"
        + "blah blah\r\n"
        + "--cool_boundary\r\n"
        + "Content-Disposition: form-data; name=\"async_field\"\r\n"
        + "\r\n"
        + token.repeat(count) + "\r\n"
        + "--cool_boundary\r\n"
        + "Content-Disposition: form-data; name=\"sync_field_2\"\r\n"
        + "\r\n"
        + "blah blah blah\r\n"
        + "--cool_boundary--\r\n";
    var asyncPublisher = FlowAdapters.toFlowPublisher(new AsyncIterablePublisher<>(
        Stream.generate(() -> US_ASCII.encode(token)).limit(count)::iterator,
        ForkJoinPool.commonPool()));
    var body = newBuilder()
        .boundary("cool_boundary")
        .formPart("sync_field_1", ofString("blah blah"))
        .formPart("async_field", fromPublisher(asyncPublisher, token.length() * count))
        .formPart("sync_field_2", ofString("blah blah blah"))
        .build();
    assertContentEquals(expected, body, US_ASCII);
  }

  @Test
  void bodyWithUnknownLength() {
    var body = newBuilder()
        .boundary("cool_boundary")
        .part(Part.create(headers(), ofString("I know my length")))
        .part(Part.create(headers("Foo", "bar"), ofInputStream(
            () -> new ByteArrayInputStream("I don't know my length!".getBytes(US_ASCII)))))
        .build();
    assertTrue(body.contentLength() < 0);
  }

  @Test
  void utf8HeaderValues() {
    var expected =
          "--cool_boundary\r\n"
        + "Utf8-Header: πω\r\n"
        + "\r\n"
        + "blah blah\r\n"
        + "--cool_boundary\r\n"
        + "Content-Disposition: form-data; name=\"utf8_fięld\"; filename=\"utf8_ƒile_ñame\"\r\n"
        + "\r\n"
        + "blah blah blah\r\n"
        + "--cool_boundary--\r\n";
    var body = newBuilder()
        .boundary("cool_boundary")
        .part(Part.create(headers("Utf8-Header", "πω"), ofString("blah blah")))
        .formPart("utf8_fięld", "utf8_ƒile_ñame", ofString("blah blah blah"))
        .build();
    assertContentEquals(expected, body, UTF_8);
  }

  @Test
  void nameWithBackslashAndQuote() {
    var expected =
          "--cool_boundary\r\n"
        + "Content-Disposition: form-data; name=\"field\\\\name\"; filename=\"\\\"file\\\\name\\\"\"\r\n"
        + "\r\n"
        + "escaping is a mess\r\n"
        + "--cool_boundary--\r\n";
    var body = newBuilder()
        .boundary("cool_boundary")
        .formPart("field\\name", "\"file\\name\"", ofString("escaping is a mess"))
        .build();
    assertContentEquals(expected, body, US_ASCII);
  }

  @Test
  void failingPart() {
    var body = newBuilder()
        .formPart("good_part", ofString("such wow"))
        .formPart("bad_part", fromPublisher(new FailedPublisher<>(TestException::new)))
        .build();
    var ex = assertThrows(CompletionException.class, () -> BodyCollector.collect(body));
    var cause = ex.getCause();
    assertNotNull(cause);
    assertEquals(TestException.class, cause.getClass());
  }

  private static MultipartBodyPublisher.Builder onePart() {
    return newBuilder().textPart("some", "thing");
  }

  private static String boundaryOf(MultipartBodyPublisher.Builder builder) {
    return builder.build().boundary();
  }

  private static void assertIllegalArg(Executable action) {
    assertThrows(IllegalArgumentException.class, action);
  }

  private static void assertContentEquals(
      String expected, MultipartBodyPublisher body, Charset charset) {
    var bodyContent = BodyCollector.collect(body);
    var expectedContent = charset.encode(expected);
    if (body.contentLength() >= 0) {
      assertEquals(bodyContent.remaining(), body.contentLength());
      assertEquals(expectedContent.remaining(), body.contentLength());
    }
    int mismatch = expectedContent.mismatch(bodyContent);
    assertEquals(-1, mismatch);
  }
}
