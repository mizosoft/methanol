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

package com.github.mizosoft.methanol.samples.crawler;

import static com.github.mizosoft.methanol.MediaType.TEXT_HTML;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MoreBodySubscribers;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.TypeRef;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpResponse.BodySubscribers;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import org.jsoup.nodes.Document;

public class Crawler {

  private final URI inceptionUri;
  private final int maxVisits;

  private final Methanol client =
      Methanol.newBuilder()
          .defaultHeader("Accept", TEXT_HTML.toString())
          .followRedirects(Redirect.NORMAL)
          .build();

  private final Set<URI> visited = new HashSet<>();
  private final Queue<URI> toVisit = new ArrayDeque<>();

  Crawler(URI inceptionUri, int maxVisits) {
    this.inceptionUri = inceptionUri;
    this.maxVisits = maxVisits;
  }

  void drain() {
    toVisit.add(inceptionUri);

    int visits = 0;
    URI nextUri;
    while (visits < maxVisits && (nextUri = toVisit.poll()) != null) {
      try {
        if (visit(nextUri)) visits++;
      } catch (Exception e) {
        System.out.printf("%s -> (failed, %s)%n", nextUri, e);
      }
    }

    toVisit.clear();
    visited.clear();
  }

  boolean visit(URI uri) throws IOException, InterruptedException {
    if (uri == null
        || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
        || !visited.add(uri)) return false;

    var response =
        client.send(
            MutableRequest.GET(uri),
            info ->
                info.headers()
                    .firstValue("Content-Type")
                    .map(MediaType::parse)
                    .filter(TEXT_HTML::isCompatibleWith)
                    .map(type -> MoreBodySubscribers.ofObject(TypeRef.from(Document.class), type))
                    .orElseGet(() -> BodySubscribers.replacing(null))); // Ignore if not an HTML page

    var document = response.body();
    if (document != null) { // Received an HTML page
      onPageReceived(uri, response.statusCode(), document);

      for (var element : document.select("a[href]")) {
        toVisit.add(withoutFragment(response.uri().resolve(element.attr("href").trim())));
      }
    }

    return true;
  }

  void onPageReceived(URI uri, int responseCode, Document document) {
    System.out.printf("%s -> (%d, %s)%n", uri, responseCode, document.title());
  }

  /** Removes fragment to not visit the same page more than once but with different fragments. */
  private static URI withoutFragment(URI uri) {
    try {
      return new URI(uri.getScheme(), uri.getSchemeSpecificPart(), null);
    } catch (URISyntaxException e) {
      throw new IllegalArgumentException("" + uri, e);
    }
  }

  public static void main(String[] args) {
    new Crawler(URI.create("https://en.wikipedia.org/wiki/Bohemian_Rhapsody"), 100).drain();
  }
}
