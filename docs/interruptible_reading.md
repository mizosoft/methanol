# Interruptible Reading

Reading from blocking sources like `InputStreams` isn't always avoidable. Once they're needed, JDK's
`BodyHandlers::ofInputStream` can be used to obtain an `HttpResponse<InputStream>`. However, reading
from such stream blocks your threads indefinitely, which causes troubles when you want to close the
application or change contexts amid reading. Methanol has support for [interruptible channels][interruptible-channel-jdk].
These are asynchronously closeable and respond to thread interrupts. Using them, you can
voluntarily halt reading operations when they're not relevant anymore.

`MoreBodySubscibers` has interruptible `ReadableByteChannel` and `Reader` implementations.
Use JDK's `Channels::newInputStream` to get an `InputStream` from an interruptible
`ReadableByteChannel` when input streams is what you need.

## Example - Interruptible Body Processing

Here's an example of a hypothetical component that processes the response from a `ReadableByteChannel`.
When the task is to be discarded, reader threads are interrupted by shutting down the owning
`ExecutorService`. This closes open channels and instructs them to halt blocking reads.

```java
class BodyProcessor {
  final ExecutorService executorService = Executors.newCachedThreadPool();
  final Methanol client = Methanol.create();

  CompletableFuture<Void> processAsync(HttpRequest request, Consumer<ByteBuffer> processAction) {
    return client.sendAsync(request, MoreBodyHandlers.ofByteChannel())
        .thenApplyAsync(res -> {
          var buffer = ByteBuffer.allocate(8 * 1024);
          try (var channel = res.body()) {
            while (channel.read(buffer.clear()) >= 0) {
              processAction.accept(buffer.flip());
            }
          } catch (ClosedByInterruptException | ClosedChannelException ignored) {
            // The thread was interrupted due to ExecutorService shutdown
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          return null;
        }, executorService);
  }

  void terminate() { executorService.shutdownNow(); }
}
```

[interruptible-channel-jdk]: https://docs.oracle.com/en/java/javase/16/docs/api/java.base/java/nio/channels/InterruptibleChannel.html
