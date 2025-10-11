# Streaming Requests

`MoreBodyPublishers` provides publishers for asynchronously streaming the request body into an `OutputStream` or a `WritableByteChannel`.

Let's say your sever supports compressed requests. If you're sending a large file, you might want to send it in gzip.
    
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

`MoreBodyPublishers::ofOutputStream` accepts a callback that takes the `OutputStream` to stream to. The callback is invoked on the given executor. 

The stream buffers written content in case the consumer (HTTP client) can't keep up with the producer (the publisher).
In such case, consumed memory is bounded by blocking the writer as long as the size of unconsumed data is hitting a certain threshold, which is computed as: 

```java
int memoryQuota = Integer.getInteger("com.github.mizosoft.methanol.flow.prefetch", 8) * Integer.getInteger("jdk.httpclient.bufsize", 16 * 1024);
```

The stream may also buffer content temporarily til an inner buffer (with size `Integer.getInteger("jdk.httpclient.bufsize", 16 * 1024)`) becomes full. You can use `OutputStream::flush` to make such content immediately available for consumption.

The stream is closed automatically after the callback. If the callback fails, the request is completed exceptionally.

As of 1.8.0, this is the recommended way for streaming requests rather than using [`WritableBodyPublisher`][writablebodypublisher_javadoc] directly.

[writablebodypublisher_javadoc]: https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/WritableBodyPublisher.html
