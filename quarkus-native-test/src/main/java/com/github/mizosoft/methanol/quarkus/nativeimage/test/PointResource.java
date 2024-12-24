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

package com.github.mizosoft.methanol.quarkus.nativeimage.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.testing.TestUtils;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import java.net.URI;
import mockwebserver3.Dispatcher;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;

@Path("/point")
public class PointResource {
  private final Methanol client = Methanol.create();
  private final MockWebServer server = new MockWebServer();
  private final URI serverUri = server.url("/").uri();

  public PointResource() {
    server.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest recordedRequest) {
            return new MockResponse()
                .setBody(
                    new okio.Buffer()
                        .write(TestUtils.gzip(recordedRequest.getBody().readString(UTF_8))))
                .setHeader("Content-Encoding", "gzip");
          }
        });
  }

  @GET
  @Produces(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
  @Consumes(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
  public Point pointPingPong(@QueryParam("x") int x, @QueryParam("y") int y) throws Exception {
    return client
        .send(
            MutableRequest.POST(serverUri, new Point(x, y), MediaType.APPLICATION_JSON),
            MoreBodyHandlers.ofObject(Point.class))
        .body();
  }
}
