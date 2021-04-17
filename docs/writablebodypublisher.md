# WritableBodyPublisher

Using `WritableBodyPublisher`, you can stream the request body through an `OutputStream` or a `WritableByteChannel`,
possibly asynchronously.

## Example - Gzipped Uploads

Let's say your sever supports compressed requests. You'd want your file uploads to be faster, so you
compress the request body with gzip.

```java
final Methanol client = Methanol.create();

CompletableFuture<HttpResponse<Void>> postAsync(Path file) {
  var requestBody = WritableBodyPublisher.create();
  var request = MutableRequest.POST("https://example.com", requestBody)
      .header("Content-Encoding", "gzip");

  CompletableFuture.runAsync(() -> {
    try (var gzipOut = new GZIPOutputStream(requestBody.outputStream())) {
      Files.copy(file, gzipOut);
    } catch (IOException ioe) {
      requestBody.closeExceptionally(ioe);
    }
  });

  return client.sendAsync(request, BodyHandlers.discarding());
}
```

`WritableBodyPublisher` acts as a pipe which connects `OutputStream` and `BodyPublisher` backends.
It may buffer content temporarily in case the consumer can't keep up with the producer, or till an
inner buffer becomes full. You can use `WritableBodyPublisher::flush`to make any buffered content
available for consumption. After you're done writing, call `close()` or `closeExceptionally(Throwable)`
to complete the request either normally or exceptionally.
