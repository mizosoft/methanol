# Progress Tracking

You can track download & upload progress using Methanol's `ProgressTracker`.

## Setup

A `ProgressTracker` controls the rate at which progress events are propagated using two thresholds:
bytes transferred & time passed, both calculated since the last event. 

=== "Byte count threshold"

    ```java
    // Receive a progress event at least each 50 kBs of data
    var tracker = ProgressTracker.newBuilder()
        .bytesTransferredThreshold(50 * 1024)
        .build();
    ```

=== "Time passed threshold"

    ```java
    // Receive a progress event at least each half a second
    var tracker = ProgressTracker.newBuilder()
        .timePassedThreshold(Duration.ofSeconds(1).dividedBy(2))
        .build();
    ```

!!! tip
    You can use the builder to set an `Executor` that's used for dispatching progress events to 
    your listener. That's useful in case your listener does something like GUI updates.
    You'd want it to be invoked in the GUI thread rather than    an arbitrary HTTP client thread.

    ```java hl_lines="3"
    var tracker = ProgressTracker.newBuilder()
        .bytesTransferredThreshold(50 * 1024)
        .executor(javafx.application.Platform::runLater)
        .build();
    ```

## Usage

You track download progress by attaching a `Listener` to a response's `BodyHandler`. Similarly, upload
progress is tracked by registering a `Listener` with a request's `BodyPublisher`.

=== "Track downloads"

    ```java
    final Methanol client = Methanol.create();
    
    final ProgressTracker tracker = ProgressTracker.newBuilder()
        .bytesTransferredThreshold(60 * 1024) // 60 kB
        .build();
        
    HttpResponse<Path> downloadVeryInterestingVideo() throws IOException, InterruptedException {
      var request = MutableRequest.GET("https://i.imgur.com/NYvl8Sy.mp4");

      var downloadingBodyHandler = BodyHandlers.ofFile(
          Path.of("interesting-video.mp4"), CREATE, WRITE);
      var trackingBodyHandler = tracker.tracking(downloadingBodyHandler, this::onProgress);
      
      return client.send(request, trackingBodyHandler);
    }
    
    void onProgress(Progress progress) {
      if (progress.determinate()) { // Overall progress can be measured
        var percent = 100 * progress.value();
        System.out.printf(
            "Downloaded %d from %d bytes (%.2f%%)%n", 
            progress.totalBytesTransferred(), progress.contentLength(), percent);
      } else {
        System.out.println("Downloaded " + progress.totalBytesTransferred());
      }

      if (progress.done()) {
        System.out.println("Done!");
      }
    }
    ```

=== "Track uploads"

    ```java
    final Methanol client = Methanol.create();
    
    final ProgressTracker tracker = ProgressTracker.newBuilder()
        .bytesTransferredThreshold(60 * 1024) // 60 kB
        .build();
        
    <T> HttpResponse<T> upload(Path file, BodyHandler<T> bodyHandler)
        throws IOException, InterruptedException {
      var trackingRequestBody = tracker.tracking(BodyPublishers.ofFile(file), this::onProgress);
      var request = MutableRequest.POST("https://httpbin.org/post", trackingRequestBody);
      
      return client.send(request, bodyHandler);
    }
    
    void onProgress(Progress progress) {
      if (progress.determinate()) { // Overall progress can be measured
        var percent = 100 * progress.value();
        System.out.printf(
            "Downloaded %d from %d bytes (%.2f%%)%n", 
            progress.totalBytesTransferred(), progress.contentLength(), percent);
      } else {
        System.out.println("Downloaded " + progress.totalBytesTransferred());
      }

      if (progress.done()) {
        System.out.println("Done!");
      }
    }
    ```

[comment]: <> (TODO mention multipart tracking?)
