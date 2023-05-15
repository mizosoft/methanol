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

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.function.Predicate.isEqual;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.mizosoft.methanol.ProgressTracker.Progress;
import com.github.mizosoft.methanol.testing.BodyCollector;
import com.github.mizosoft.methanol.testing.MockWebServerExtension;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import java.net.URI;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockWebServerExtension.class)
class ProgressTrackerClientTest {
  private MockWebServer server;
  private URI serverUri;
  private Methanol client;
  private ProgressTracker tracker;

  @BeforeEach
  void setUp(MockWebServer server) {
    this.server = server;
    serverUri = server.url("/").uri();
    client = Methanol.create();
    tracker = ProgressTracker.create();
  }

  @Test
  void trackSmallDownload() throws Exception {
    server.enqueue(new MockResponse().setBody("Pikachu"));

    var subscriber = new TestSubscriber<List<ByteBuffer>>();
    var progressEvents = new CopyOnWriteArrayList<Progress>();
    client.send(
        GET(serverUri),
        tracker.tracking(BodyHandlers.fromSubscriber(subscriber), progressEvents::add));
    assertThat(progressEvents).isNotEmpty();

    var body =
        BodyCollector.collect(
            subscriber.pollAll().stream()
                .flatMap(Collection::stream)
                .collect(Collectors.toUnmodifiableList()));
    assertThat(UTF_8.decode(body).toString()).isEqualTo("Pikachu");

    assertThat("Pikachu".length())
        .isEqualTo(progressEvents.stream().mapToLong(Progress::bytesTransferred).sum());

    var lastProgress = progressEvents.get(progressEvents.size() - 1);
    assertThat(lastProgress.done()).isTrue();
    assertThat("Pikachu".length()).isEqualTo(lastProgress.totalBytesTransferred());
    assertThat(progressEvents)
        .map(Progress::contentLength)
        .allMatch(isEqual((long) "Pikachu".length()));
  }
}
