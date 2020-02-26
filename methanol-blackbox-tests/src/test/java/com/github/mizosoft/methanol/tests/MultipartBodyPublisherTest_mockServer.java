package com.github.mizosoft.methanol.tests;

import static com.github.mizosoft.methanol.MoreBodyPublishers.ofMediaType;
import static com.github.mizosoft.methanol.MoreBodyPublishers.ofObject;
import static com.github.mizosoft.methanol.testutils.TestUtils.headers;
import static com.github.mizosoft.methanol.testutils.TestUtils.load;
import static com.github.mizosoft.methanol.testutils.TestUtils.loadAscii;
import static java.net.http.HttpRequest.BodyPublishers.fromPublisher;
import static java.net.http.HttpResponse.BodyHandlers.discarding;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MultipartBodyPublisher;
import com.github.mizosoft.methanol.MultipartBodyPublisher.Part;
import com.github.mizosoft.methanol.testutils.BuffIterator;
import com.github.mizosoft.methanol.testutils.RegistryFileTypeDetector;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Date;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.reactivestreams.FlowAdapters;
import org.reactivestreams.example.unicast.AsyncIterablePublisher;

public class MultipartBodyPublisherTest_mockServer extends Lifecycle {

  @Test
  void sendFormBody(@TempDir Path tempDir) throws Exception {
    var tweet = new Tweet();
    tweet.sender = "Albert Einstein";
    tweet.content = "Is math related to science?";
    tweet.dateSent = new Date(1921, Calendar.NOVEMBER, 9);
    var theoryFile = Files.createFile(tempDir.resolve("relativity.theory"));
    Files.writeString(theoryFile, "Time is relative bro");
    RegistryFileTypeDetector.register("theory", MediaType.parse("application/x-theory"));
    var multipartBody = MultipartBodyPublisher.newBuilder()
        .boundary("my_awesome_boundary")
        .textPart("action", "sendTweet")
        .formPart("tweet", ofObject(tweet, MediaType.of("application", "json")))
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
            + "{\"sender\":\"Albert Einstein\",\"content\":\"Is math related to science?\",\"dateSent\":58438879200000}\r\n"
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
    var executor = Executors.newFixedThreadPool(8);
    try {
      var cls = MultipartBodyPublisherTest_mockServer.class;
      var multipartAlternative = MultipartBodyPublisher.newBuilder()
          .mediaType(MediaType.of("multipart", "alternative"))
          .boundary("my_cool_boundary")
          .part(Part.create(
              headers("Content-Transfer-Encoding", "quoted-printable"),
              ofObject("Hey, that's pretty good", MediaType.of("text", "plain"))))
          .part(Part.create(
              headers("Content-Transfer-Encoding", "quoted-printable"),
              ofObject("<h1>Hey, that's pretty good</h1>", MediaType.of("text", "html"))))
          .build();
      var attachment = load(cls, "/payload/alice.txt");
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
      assertEquals(sentRequest.getHeader("Content-Length"), Long.toString(multipartMixed.contentLength()));
      assertEquals(
          String.format(expected_template, loadAscii(cls, "/payload/alice.txt")),
          sentRequest.getBody().readUtf8());
    } finally {
      executor.shutdown();
    }
  }

  static final class Tweet {

    public String sender;
    public String content;
    public Date dateSent;

    public Tweet() {
    }
  }
}
