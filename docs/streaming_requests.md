# Streaming Requests

`MoreBodyPublishers` provides publishers for asynchronously streaming the request body into an `OutputStream` or a `WritableByteChannel`.

Let's say your server supports compressed requests. If you're sending a large file, you might want to send it in gzip.
    
```java
final Methanol client = Methanol.create();
final Executor executor = Executors.newVirtualThreadPerTaskExecutor();

HttpResponse<Void> postGzipped(Path file) {
  return client.send(
      MutableRequest.POST(
              "https://example.com",
              MoreBodyPublishers.ofOutputStream(out -> {
                try (var gzipOut = new GZIPOutputStream(out)) {
                  Files.copy(file, gzipOut);
                }
              }, executor))
          .header("Content-Encoding", "gzip"),
      BodyHandlers.discarding());
}
```

`MoreBodyPublishers::ofOutputStream` accepts a callback that takes the `OutputStream` to stream to. The callback is invoked on the given executor only 
when the request body starts streaming. The stream is closed automatically after the callback. If the callback fails, the request is completed exceptionally.

The stream automatically manages memory to prevent unbounded buffering. When data is written faster than the HTTP client can transmit it, the writer callback will block once buffered data exceeds a threshold.
This prevents out-of-memory errors when streaming large bodies. The threshold is calculated as follows:

```java
int memoryQuota = Integer.getInteger("com.github.mizosoft.methanol.flow.prefetch", 8) * Integer.getInteger("jdk.httpclient.bufsize", 16 * 1024);
```

The stream may also buffer content temporarily til an inner buffer (with size `Integer.getInteger("jdk.httpclient.bufsize", 16 * 1024)`) becomes full before making it available for consumption by the HTTP client.
You can use `OutputStream::flush` to make such content immediately available for consumption, but this is generally not needed.

As of 1.8.0, this is the recommended way for streaming requests rather than using [`WritableBodyPublisher`][writablebodypublisher_javadoc] directly, which is prone to being mis-used.

[writablebodypublisher_javadoc]: https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/WritableBodyPublisher.html
