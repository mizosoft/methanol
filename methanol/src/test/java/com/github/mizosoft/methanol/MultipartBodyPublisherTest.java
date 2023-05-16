/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.testing.TestUtils.headers;
import static com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat;
import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.from;

import com.github.mizosoft.methanol.MultipartBodyPublisher.Part;
import com.github.mizosoft.methanol.testing.*;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

@ExtendWith(ExecutorExtension.class)
class MultipartBodyPublisherTest {
  @Test
  void defaultMediaType() {
    assertThat(onePart().build().mediaType())
        .returns("multipart", from(MediaType::type))
        .returns("form-data", from(MediaType::subtype));
  }

  @Test
  void customSubtype() {
    assertThat(onePart().mediaType(MediaType.parse("multipart/mixed")).build().mediaType())
        .returns("multipart", from(MediaType::type))
        .returns("mixed", from(MediaType::subtype));
  }

  @Test
  void customBoundary() {
    assertThat(onePart().boundary("my_boundary").build())
        .returns("my_boundary", from(MultipartBodyPublisher::boundary));

    var mediaType =
        MediaType.parse("multipart/alternative").withParameter("boundary", "my_boundary");

    assertThat(onePart().mediaType(mediaType).build())
        .returns("my_boundary", from(MultipartBodyPublisher::boundary));

    assertThat(onePart().mediaType(mediaType).boundary("my_other_boundary").build())
        .returns("my_other_boundary", from(MultipartBodyPublisher::boundary));

    assertThat(onePart().boundary("my_other_boundary").mediaType(mediaType).build())
        .returns("my_boundary", from(MultipartBodyPublisher::boundary));
  }

  @Test
  void invalidBoundary() {
    var builder = MultipartBodyPublisher.newBuilder();
    assertThatIllegalArgumentException().isThrownBy(() -> builder.boundary("i||ega|_boundary"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder.boundary("b".repeat(71))); // Illegal boundary size
    assertThatIllegalArgumentException().isThrownBy(() -> builder.boundary(""));
    assertThatIllegalArgumentException().isThrownBy(() -> builder.boundary("ends_with_space "));
  }

  @Test
  void invalidBoundaryFromMediaType() {
    var builder = MultipartBodyPublisher.newBuilder();
    var mediaType = MediaType.parse("multipart/form-data");
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> builder.mediaType(mediaType.withParameter("boundary", "i||ega|_boundary")));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder.mediaType(mediaType.withParameter("boundary", "b".repeat(71))));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder.mediaType(mediaType.withParameter("boundary", "")));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> builder.mediaType(mediaType.withParameter("boundary", "ends_with_space ")));
  }

  @Test
  void invalidMediaType() {
    var builder = MultipartBodyPublisher.newBuilder();
    assertThatIllegalArgumentException()
        .isThrownBy(() -> builder.mediaType(MediaType.parse("text/plain")));
  }

  @Test
  void createPartWithContentTypeHeaderAndMimeBodyPublisher() {
    // Content-Type can't be defined in two different places
    var headers = headers("Content-Type", "application/octet-stream");
    var publisher =
        MoreBodyPublishers.ofMediaType(
            BodyPublishers.ofString("something"), MediaType.parse("text/plain"));
    assertThatIllegalArgumentException().isThrownBy(() -> Part.create(headers, publisher));
  }

  @Test
  void createPartWithInvalidHeaderName() {
    var headers = headers("Illeg@l-Token", "whatever");
    var publisher = BodyPublishers.ofString("something");
    assertThatIllegalArgumentException().isThrownBy(() -> Part.create(headers, publisher));
  }

  @Test
  void addNoParts() {
    var builder = MultipartBodyPublisher.newBuilder();
    assertThatIllegalStateException().isThrownBy(builder::build);
  }

  @Test
  void serializeOnePart() {
    var body =
        MultipartBodyPublisher.newBuilder()
            .boundary("my_boundary")
            .part(Part.create(headers("X-Foo", "bar"), BodyPublishers.ofString("some content")))
            .build();
    verifyThat(body)
        .succeedsWith(
            "--my_boundary\r\n"
                + "X-Foo: bar\r\n"
                + "\r\n"
                + "some content\r\n"
                + "--my_boundary--\r\n");
  }

  @Test
  void serializeFormParts() {
    var body =
        MultipartBodyPublisher.newBuilder()
            .boundary("my_boundary")
            .formPart("text_field", BodyPublishers.ofString("Hello World"))
            .formPart(
                "file_field",
                "hola.txt",
                MoreBodyPublishers.ofMediaType(
                    BodyPublishers.ofString("Hola Mundo"),
                    MediaType.parse("text/plain; charset=utf-8")))
            .build();
    verifyThat(body)
        .succeedsWith(
            "--my_boundary\r\n"
                + "Content-Disposition: form-data; name=\"text_field\"\r\n"
                + "\r\n"
                + "Hello World\r\n"
                + "--my_boundary\r\n"
                + "Content-Disposition: form-data; name=\"file_field\"; filename=\"hola.txt\"\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\n"
                + "\r\n"
                + "Hola Mundo\r\n"
                + "--my_boundary--\r\n");
  }

  @Test
  void serializeTextPartUtf8() {
    var body =
        MultipartBodyPublisher.newBuilder()
            .boundary("my_boundary")
            .textPart("important_question", "Is math related to science? £_£")
            .textPart("less_important_question", "Is earth flat? $_$", US_ASCII)
            .build();
    verifyThat(body)
        .succeedsWith(
            "--my_boundary\r\n"
                + "Content-Disposition: form-data; name=\"important_question\"\r\n"
                + "\r\n"
                + "Is math related to science? £_£\r\n"
                + "--my_boundary\r\n"
                + "Content-Disposition: form-data; name=\"less_important_question\"\r\n"
                + "\r\n"
                + "Is earth flat? $_$\r\n"
                + "--my_boundary--\r\n");
  }

  @Test
  void serializeFileParts(@TempDir Path tempDir) throws IOException {
    var crazyFile =
        Files.createFile(tempDir.resolve("crazy_file.impossible.to.be.detected.by.anything.else"));
    var saneFile = Files.createFile(tempDir.resolve("sane_file.txt"));
    RegistryFileTypeDetector.register(
        "impossible.to.be.detected.by.anything.else", MediaType.parse("application/x-crazy"));
    Files.writeString(crazyFile, "ey yo i'm trippin");
    Files.writeString(saneFile, "we live in a society");

    var body =
        MultipartBodyPublisher.newBuilder()
            .boundary("my_boundary")
            .filePart("crazy_file", crazyFile)
            .filePart("sane_file", saneFile, MediaType.parse("text/plain"))
            .build();
    verifyThat(body)
        .succeedsWith(
            "--my_boundary\r\n"
                + "Content-Disposition: form-data; name=\"crazy_file\"; filename=\"crazy_file.impossible.to.be.detected.by.anything.else\"\r\n"
                + "Content-Type: application/x-crazy\r\n"
                + "\r\n"
                + "ey yo i'm trippin\r\n"
                + "--my_boundary\r\n"
                + "Content-Disposition: form-data; name=\"sane_file\"; filename=\"sane_file.txt\"\r\n"
                + "Content-Type: text/plain\r\n"
                + "\r\n"
                + "we live in a society\r\n"
                + "--my_boundary--\r\n");
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void asyncBodyPart(Executor executor) {
    var token = "REPEAT ME!\r\n";
    var encodedToken = US_ASCII.encode(token);
    var repeatCount = 10000;
    var asyncPublisher =
        new IterablePublisher<>(
            Stream.generate(encodedToken::duplicate).limit(repeatCount)::iterator, executor);
    var body =
        MultipartBodyPublisher.newBuilder()
            .boundary("my_boundary")
            .formPart("sync_field_1", BodyPublishers.ofString("something"))
            .formPart("async_field", BodyPublishers.fromPublisher(asyncPublisher))
            .formPart("sync_field_2", BodyPublishers.ofString("another thing"))
            .build();
    verifyThat(body)
        .succeedsWith(
            "--my_boundary\r\n"
                + "Content-Disposition: form-data; name=\"sync_field_1\"\r\n"
                + "\r\n"
                + "something\r\n"
                + "--my_boundary\r\n"
                + "Content-Disposition: form-data; name=\"async_field\"\r\n"
                + "\r\n"
                + token.repeat(repeatCount)
                + "\r\n"
                + "--my_boundary\r\n"
                + "Content-Disposition: form-data; name=\"sync_field_2\"\r\n"
                + "\r\n"
                + "another thing\r\n"
                + "--my_boundary--\r\n");
  }

  @Test
  void bodyWithUnknownLength() {
    var body =
        MultipartBodyPublisher.newBuilder()
            .boundary("my_boundary")
            .part(Part.create(headers(), BodyPublishers.ofString("I know my length")))
            .part(
                Part.create(
                    headers("Foo", "bar"),
                    BodyPublishers.ofInputStream(
                        () ->
                            new ByteArrayInputStream(
                                "I don't know my length!".getBytes(US_ASCII)))))
            .build();
    verifyThat(body).hasContentLength(-1);
  }

  @Test
  void utf8HeaderValues() {
    var body =
        MultipartBodyPublisher.newBuilder()
            .boundary("my_boundary")
            .part(Part.create(headers("X-Utf8-Header", "πω"), BodyPublishers.ofString("something")))
            .formPart("utf8_fięld", "utf8_ƒile_ñame", BodyPublishers.ofString("another thing"))
            .build();
    verifyThat(body)
        .succeedsWith(
            "--my_boundary\r\n"
                + "X-Utf8-Header: πω\r\n"
                + "\r\n"
                + "something\r\n"
                + "--my_boundary\r\n"
                + "Content-Disposition: form-data; name=\"utf8_fięld\"; filename=\"utf8_ƒile_ñame\"\r\n"
                + "\r\n"
                + "another thing\r\n"
                + "--my_boundary--\r\n");
  }

  @Test
  void nameWithBackslashAndQuote() {
    var body =
        MultipartBodyPublisher.newBuilder()
            .boundary("my_boundary")
            .formPart("field\\name", "\"file\\name\"", BodyPublishers.ofString("something"))
            .build();
    verifyThat(body)
        .succeedsWith(
            "--my_boundary\r\n"
                + "Content-Disposition: form-data; name=\"field\\\\name\"; filename=\"\\\"file\\\\name\\\"\"\r\n"
                + "\r\n"
                + "something\r\n"
                + "--my_boundary--\r\n");
  }

  @Test
  void failingPart() {
    var body =
        MultipartBodyPublisher.newBuilder()
            .formPart("good_part", BodyPublishers.ofString("something"))
            .formPart(
                "bad_part",
                BodyPublishers.fromPublisher(new FailingPublisher<>(TestException::new)))
            .build();
    verifyThat(body).failsWith(TestException.class);
  }

  private static MultipartBodyPublisher.Builder onePart() {
    return MultipartBodyPublisher.newBuilder().textPart("some", "thing");
  }
}
