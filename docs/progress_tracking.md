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
        .timePassedThreshold(Duration.ofSeconds(1).divideBy(2))
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
        .timePassedThreshold(Duration.ofMillis(100))
        .build();
        
    HttpResponse<Path> downloadVeryInterestingVideo() throws IOException, InterruptedException {
      var request = MutableReqeust.GET("https://i.imgur.com/OBmbMPV.mp4");

      var downloadingBodyHandler = BodyHandlers.ofFile(
          Path.of("interesting-video.mp4"), CREATE, WRITE);
      var trackingBodyHandler = tracker.tracking(downloadingBodyHandler, this::onProgress);
      
      return client.send(request, trackingBodyHandler);
    }
    
    void onProgress(Progress progress) {
      if (progress.done()) {
        System.out.println("Done!");
      } else if (progress.determinate()) { // Overall progress can be measured
        var percent = 100 * progress.value();
        var roundedPercent = Math.round(100 * percent) / 100.0;
        System.out.printf(
            "Downloaded %d from %d (%d)%n", 
            progress.bytesTransferred(), progress.contentLength(), roundedPercent);
      } else {
        System.out.println("Downloaded " + progress.bytesTransferred());
      }
    }
    ```

=== "Track uploads"

    ```java
    final Methanol client = Methanol.create();
    
    final ProgressTracker tracker = ProgressTracker.newBuilder()
        .timePassedThreshold(Duration.ofMillis(100))
        .build();
        
    <T> HttpResponse<T> upload(Path file, BodyHandler<T> bodyHandler)
        throws IOException, InterruptedException {
      var trackingRequestBody = tracker.tracking(BodyPublishers.ofFile(file), this::onProgress);
      var request = MutableReqeust.POST("https://httpbin.org/post", trackingRequestBody);
      
      return client.send(request, bodyHandler);
    }
    
    void onProgress(Progress progress) {
      if (progress.done()) {
        System.out.println("Done!");
      } if (progress.determinate()) { // Overall progress can be measured
        var percent = 100 * progress.value();
        var roundedPercent = Math.round(100 * percent) / 100.0;
        System.out.printf(
            "Uploaded %d from %d (%d)%n", 
            progress.bytesTransferred(), progress.contentLength(), roundedPercent);
      } else {
        System.out.println("Uploaded " + progress.bytesTransferred());
      }
    }
    ```

!!! tip
    By default, the tracker doesn't fire `0%` and `100%` progress events. You can enable builder's
    `enclosedProgress` option to receive these. That way, you're notified when downloads or uploads
    begin or end.

[comment]: <> (TODO mention multipart tracking?)
    
