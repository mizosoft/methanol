/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.springboot;

import static com.github.mizosoft.methanol.testing.TestUtils.gzip;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.github.mizosoft.methanol.MoreBodyPublishers;
import com.github.mizosoft.methanol.MutableRequest;
import java.net.URI;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Controller {
  private final Methanol client = Methanol.create();
  private final MockWebServer server = new MockWebServer();
  private final URI serverUri = server.url("/").uri();

  public Controller() {
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest recordedRequest) {
            return new MockResponse()
                .setBody(new okio.Buffer().write(gzip(recordedRequest.getBody().readString(UTF_8))))
                .setHeader("Content-Encoding", "gzip");
          }
        });
  }

  @GetMapping
  public Point pointPingPong(
      @RequestParam(value = "x", defaultValue = "0") int x,
      @RequestParam(value = "y", defaultValue = "0") int y)
      throws Exception {
    return client
        .send(
            MutableRequest.POST(
                serverUri,
                MoreBodyPublishers.ofObject(new Point(x, y), MediaType.APPLICATION_JSON)),
            MoreBodyHandlers.ofObject(Point.class))
        .body();
  }

  public record Point(int x, int y) {}
}
