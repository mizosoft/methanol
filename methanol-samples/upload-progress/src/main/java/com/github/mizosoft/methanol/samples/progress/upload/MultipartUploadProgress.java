/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.samples.progress.upload;

import com.github.mizosoft.methanol.Methanol;
import com.github.mizosoft.methanol.MoreBodyHandlers;
import com.github.mizosoft.methanol.MultipartBodyPublisher;
import com.github.mizosoft.methanol.MutableRequest;
import com.github.mizosoft.methanol.ProgressTracker;
import com.github.mizosoft.methanol.ProgressTracker.MultipartProgress;
import java.io.FileNotFoundException;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

public class MultipartUploadProgress extends Application {
  private static final int WIDTH = 420;
  private static final int HEIGHT = 120;

  private static final String URL = "https://httpbin.org/post";

  private final Methanol client = Methanol.create();
  private final ProgressTracker tracker =
      ProgressTracker.newBuilder()
          .bytesTransferredThreshold(10 * 1024) // get an event every 10KB at least
          .executor(Platform::runLater) // execute listener on JavaFX Application Thread
          .build();

  private final FileChooser fileChooser = new FileChooser();
  private final List<Path> addedFiles = new ArrayList<>();

  private final ProgressBar mainProgressBar = new ProgressBar(0.d);
  private final ProgressBar perFileProgressBar = new ProgressBar(0.d);
  private final Label fileNameLabel = new Label("N/A");
  private final Button addFileButton = new Button("Add File");
  private final Button resetButton = new Button("Reset");
  private final Button uploadButton = new Button("Upload");
  private final List<Button> allButtons = List.of(addFileButton, resetButton, uploadButton);

  @Override
  public void start(Stage primaryStage) {
    var progressBarsGridPane = new GridPane();
    progressBarsGridPane.setHgap(10.d);
    progressBarsGridPane.setVgap(10.d);

    var notGrowable = new ColumnConstraints();
    notGrowable.setHgrow(Priority.NEVER);
    var growable = new ColumnConstraints();
    growable.setHgrow(Priority.ALWAYS);
    progressBarsGridPane.getColumnConstraints().addAll(notGrowable, growable);

    progressBarsGridPane.add(mainProgressBar, 0, 0, 2, 1);
    progressBarsGridPane.add(fileNameLabel, 0, 1);
    progressBarsGridPane.add(perFileProgressBar, 1, 1);
    fileNameLabel.setMaxWidth(100);
    mainProgressBar.setMaxWidth(Double.MAX_VALUE);
    perFileProgressBar.setMaxWidth(Double.MAX_VALUE);

    var buttonBox = new HBox(10.d);
    buttonBox.setPadding(new Insets(0.d, 18.d, 0.d, 18.d));
    buttonBox.setAlignment(Pos.CENTER);
    for (var button : allButtons) {
      HBox.setHgrow(button, Priority.ALWAYS);
      button.setMaxWidth(Double.MAX_VALUE);
      buttonBox.getChildren().add(button);
    }

    var gridPane = new GridPane();
    gridPane.setVgap(10.d);
    gridPane.setPadding(new Insets(18.d));
    gridPane.add(progressBarsGridPane, 0, 0);
    gridPane.add(buttonBox, 0, 1);
    gridPane.getColumnConstraints().add(growable);

    fileChooser.setTitle("Add File");

    setAddFileListener(primaryStage);
    setResetListener();
    setUploadListener();

    primaryStage.setTitle("Multipart Upload Progress");
    primaryStage.setScene(new Scene(gridPane, WIDTH, HEIGHT));
    primaryStage.show();
  }

  private void setAddFileListener(Stage primaryStage) {
    addFileButton.setOnAction(
        e -> {
          var file = fileChooser.showOpenDialog(primaryStage);
          if (file != null) {
            addedFiles.add(file.toPath());
          }
        });
  }

  private void setResetListener() {
    resetButton.setOnAction(e -> addedFiles.clear());
  }

  @SuppressWarnings("FutureReturnValueIgnored")
  private void setUploadListener() {
    uploadButton.setOnAction(
        e -> {
          mainProgressBar.setProgress(0.d);
          perFileProgressBar.setProgress(0.d);
          allButtons.forEach(b -> b.setDisable(true));
          uploadFilesAsync()
              .whenComplete(
                  (r, t) ->
                      Platform.runLater(
                          () -> {
                            perFileProgressBar.setProgress(0.d);
                            mainProgressBar.setProgress(0.d);
                            allButtons.forEach(b -> b.setDisable(false));
                            fileNameLabel.setText("N/A");
                          }));
        });
  }

  private CompletableFuture<Void> uploadFilesAsync() {
    var snapshot =
        addedFiles.stream().filter(Files::exists).collect(Collectors.toUnmodifiableList());
    if (snapshot.isEmpty()) {
      return CompletableFuture.completedFuture(null);
    }

    var builder = MultipartBodyPublisher.newBuilder();
    try {
      for (int i = 0; i < snapshot.size(); i++) {
        builder.filePart("file_" + i, snapshot.get(i));
      }
    } catch (FileNotFoundException e) {
      throw new UncheckedIOException(e);
    }

    var request =
        MutableRequest.POST(
            URL, tracker.trackingMultipart(builder.build(), this::onUploadProgress));
    return client
        .sendAsync(
            request,
            MoreBodyHandlers.fromAsyncSubscriber(
                BodySubscribers.discarding(),
                __ -> CompletableFuture.<Void>completedFuture(null))) // ignore body completion
        .thenApply(HttpResponse::body);
  }

  private void onUploadProgress(MultipartProgress progress) {
    if (progress.partChanged()) {
      // Currently file name has to be parsed from headers
      // Maybe a ContentDisposition class can be added in future to make this easier
      var filename =
          progress
              .part()
              .headers()
              .firstValue("Content-Disposition")
              .flatMap(MultipartUploadProgress::getFilenameFromDisposition)
              .orElse("UNKNOWN");
      fileNameLabel.setText(filename);
    }

    mainProgressBar.setProgress(progress.value());
    perFileProgressBar.setProgress(progress.partProgress().value());
  }

  private static Optional<String> getFilenameFromDisposition(String disposition) {
    int i = disposition.indexOf("filename=");
    if (i == -1) {
      return Optional.empty();
    }

    int j = disposition.indexOf(';', i);
    int n = i + 9;
    var filename = j == -1 ? disposition.substring(n) : disposition.substring(n, j);
    if (filename.length() > 2 && filename.startsWith("\"") && filename.endsWith("\"")) {
      filename = filename.substring(1, filename.length() - 1);
    }
    return Optional.of(filename);
  }

  public static void main(String[] args) {
    launch(args);
  }
}
