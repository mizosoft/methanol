# Streaming Requests

`MoreBodyPublishers` provides publishers for asynchronously streaming the request body into an `OutputStream` or a `WritableByteChannel`.

Let's say your sever supports compressed requests. If you're sending a large file, you'd want to send it in gzip.

```java
final Methanol client = Methanol.create();
final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

HttpResponse<Void> postGzipped(Path file) {
  return client.send(
      MutableRequest.POST(
              "https://example.com",
              MoreBodyPublishers.ofOutputStream(
                  out -> Files.copy(file, out), executor))
          .header("Content-Encoding", "gzip"),
      BodyHandlers.discarding());
}
```

`MoreBodyPublishers::ofOutputStream` accepts a callback that takes the `OutputStream` to stream to.
The callback is invoked on the given executor. The stream may buffer content temporarily in case the consumer can't keep up with the producer, or till an inner buffer becomes full.
You can use `OutputStream::flush`to make any buffered content available for consumption. The stream is closed automatically after the callback.
If the callback fails, the request is completed exceptionally.

As of 1.8.0, this is the recommended way for streaming requests rather than using [`WritableBodyPublisher`][writablebodypublisher_javadoc] directly.

[writablebodypublisher_javadoc]: https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/WritableBodyPublisher.html
