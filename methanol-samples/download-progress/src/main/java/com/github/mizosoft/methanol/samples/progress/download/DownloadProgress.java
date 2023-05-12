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

package com.github.mizosoft.methanol.samples.progress.download;

import static com.github.mizosoft.methanol.MutableRequest.GET;
import static java.net.http.HttpResponse.BodySubscribers.discarding;
import static java.net.http.HttpResponse.BodySubscribers.replacing;

import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.ProgressTracker;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

public class DownloadProgress extends Application {
  private static final int WIDTH = 400;
  private static final int HEIGHT = 100;

  private static final String DEFAULT_URL = "https://norvig.com/big.txt";

  private final Methanol client = Methanol.create();
  private final ProgressTracker tracker =
      ProgressTracker.newBuilder()
          .bytesTransferredThreshold(50 * 1024) // get an event each 50KB at least
          .executor(Platform::runLater) // execute listener on JavaFX Application Thread
          .build();

  private final ProgressBar progressBar = new ProgressBar(0.d);
  private final TextField urlTextField = new TextField(DEFAULT_URL);
  private final Button downloadButton = new Button("Download");

  @Override
  public void start(Stage primaryStage) {
    var gridPane = new GridPane();
    gridPane.setHgap(10.d);
    gridPane.setVgap(10.d);
    gridPane.setPadding(new Insets(18.d));

    var growable = new ColumnConstraints();
    growable.setHgrow(Priority.ALWAYS);
    var notGrowable = new ColumnConstraints();
    notGrowable.setHgrow(Priority.NEVER);
    gridPane.getColumnConstraints().addAll(growable, notGrowable);

    gridPane.add(urlTextField, 0, 0);
    gridPane.add(downloadButton, 1, 0);
    gridPane.add(progressBar, 0, 1, 2, 1);
    progressBar.setMaxWidth(Double.MAX_VALUE);
    GridPane.setHalignment(downloadButton, HPos.RIGHT);

    setDownloadListener();

    primaryStage.setTitle("Download Progress");
    primaryStage.setScene(new Scene(gridPane, WIDTH, HEIGHT));
    primaryStage.show();
  }

  private void setDownloadListener() {
    downloadButton.setOnAction(
        e -> {
          progressBar.setProgress(0.d);
          downloadButton.setDisable(true); // disable while sending and re-enable when finished
          downloadUrlAsync(urlTextField.getText())
              .whenComplete((r, t) -> Platform.runLater(() -> downloadButton.setDisable(false)));
        });
  }

  private CompletableFuture<Void> downloadUrlAsync(String url) {
    return getContentLengthAsync(url)
        .thenCompose(len -> client.sendAsync(GET(url), info -> trackingWithLength(len)))
        .thenApply(HttpResponse::body);
  }

  /** Make a HEAD request to know resource's uncompressed length for determinate progress. */
  private CompletableFuture<Long> getContentLengthAsync(String url) {
    MutableRequest request =
        MutableRequest.create(url)
            .method("HEAD", BodyPublishers.noBody())
            .headers("Accept-Encoding", "identity"); // overrides Methanol's autoAcceptEncoding

    return client
        .sendAsync(request, DownloadProgress::ofContentLength)
        .thenApply(res -> res.body().orElseThrow());
  }

  /** Tracks a {@code BodySubscriber} with a predefined length. */
  private BodySubscriber<Void> trackingWithLength(long length) {
    return tracker.tracking(discarding(), p -> progressBar.setProgress(p.value()), length);
  }

  private static BodySubscriber<OptionalLong> ofContentLength(ResponseInfo info) {
    return replacing(info.headers().firstValueAsLong("Content-Length"));
  }

  public static void main(String[] args) {
    launch(args);
  }
}
