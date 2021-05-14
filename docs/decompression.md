# Response Decompression

One caveat concerning Java's HTTP client is the lack of support for automatic response
decompression. A workaround is to use an available `InputStream` decompressor (e.g. `GZIPInputStream`)
that matches response's `Content-Encoding`. However, such approach is invasive as it forces us to deal
with `InputStreams`.

The straightforward and recommended solution is to use Methanol's [enhanced HTTP client](enhanced_httpclient.md),
which gives you transparent response decompression for `gzip` & `deflate` out of the box.

```java
final Methanol client = Methanol.create();

<T> HttpResponse<T> get(String url, BodyHandler<T> bodyHandler) throws IOException, InterruptedException {
  // No need to worry about adding Accept-Encoding and
  // decompressing the response. The client does that for you!
  return client.send(MutableRequest.GET(url), bodyHandler);
}
```

Read on if you're interested in knowing how that's accomplished or you want to extend decompression
support.

## Decoding BodyHandler

The entry point to response body decompression is [`MoreBodyHandlers::decoding`][morebodyhandlers_decoding_javadoc].
This method takes your desired `BodyHandler` and gives you one that decompresses the response body as
your handler's `BodySubscriber` receives it.

```java
var response = client.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
```

<!-- The new `BodyHandler` intercepts the response, checking if there's a `Content-Encoding` header. If
so, the body is decompressed accordingly, and your handler won't see any `Content-Encoding` or
`Content-Length` headers. This is because they're outdated in that case. Otherwise, the handler acts
as a no-op and delegates to your handler directly. -->

Note that it doesn't matter which `BodyHandler` you're using; you can have whatever response body
type you want.

## BodyDecoder

A [`BodyDecoder`][bodydecoder_javadoc] is a `BodySubscriber` with the added semantics of a `Flow.Processor`.
It intercepts the flow of bytes on its way down from the HTTP client, decoding each `List<ByteBuffer>`
individually. The decoded bytes are forwarded to a downstream `BodySubscriber`, which converts them into the desired
response body.

A `BodyDecoder.Factory` associates itself with a defined encoding that's suitable as a `Content-Encoding`
directive. Given a downstream `BodySubscriber`, the factory creates a `BodyDecoder` that forwards the
response body after decoding it using the factory's encoding. For instance, a factory associated with
`gzip` creates decoders that decompress the response using the [gzip format][gzip-rfc].

### Factory Lookup

Factories are installed as service-providers in the manner specified by Java's `ServiceLoader`. A
decoding `BodyHandler` looks up a factory associated with response's `Content-Encoding`. If found,
it's called to wrap user's `BodySubscriber`, so it receives the decompressed body. Otherwise, an
`UnsupportedOperationException` is thrown.

## Supported Encodings

The core module has support for `gzip` & `deflate` out of the box. There's also a separate
[module][methanol-brotli] providing support for [brotli].

## Extending decompression support

Adding support for more encodings or overriding supported ones is a matter of writing a `BodyDecoder`
implementation and providing a corresponding factory. However, implementing the decoder's `Flow.Publisher`
semantics can be tricky. Instead, implement an `AsyncDecoder` and wrap in an `AsyncBodyDecoder`, so
you're only concerned with your decompression logic.

### Writing an AsyncDecoder

Decoding is done as a number of `decode(source, sink)` rounds finalized by one final round, each
with the currently available input. After the final round, your `AsyncDecoder` must've completely
exhausted its source. Here's a decoder implementation that uses [jzlib] for `gzip` & `deflate`
decompression.

```java
class JZlibDecoder implements AsyncDecoder {
  private static final int BUFFER_SIZE = 8096;

  private final String encoding;
  private final com.jcraft.jzlib.Inflater inflater;
  private final ByteBuffer input = ByteBuffer.allocate(BUFFER_SIZE);
  private final ByteBuffer output = ByteBuffer.allocate(BUFFER_SIZE);

  JZlibDecoder(String encoding, com.jcraft.jzlib.JZlib.WrapperType wrapperType) {
    try {
      this.encoding = encoding;
      inflater = new com.jcraft.jzlib.Inflater(wrapperType);
      inflater.setInput(input.array());
      inflater.setOutput(output.array());
    } catch (com.jcraft.jzlib.GZIPException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public String encoding() {
    return encoding;
  }

  @Override
  public void decode(ByteSource source, ByteSink sink) throws IOException {
    synchronized (inflater) {
      while (source.hasRemaining()) {
        // Prepare input for this iteration
        inflater.setNextInIndex(0);
        inflater.setAvailIn(refillInput(source));

        // Continue inflating as long as there's more input or there's pending output

        boolean mightHavePendingOutput = false;

        inflateLoop:
        while (inflater.getAvailIn() > 0 || mightHavePendingOutput) {
          // Prepare for new output
          inflater.setNextOutIndex(0);
          inflater.setAvailOut(output.capacity());

          int status = inflater.inflate(com.jcraft.jzlib.JZlib.Z_SYNC_FLUSH);
          int outputCount = inflater.getNextOutIndex();
          sink.pushBytes(output.position(0).limit(outputCount));

          switch (status) {
            case com.jcraft.jzlib.JZlib.Z_OK:
              mightHavePendingOutput = inflater.getAvailOut() == 0;
              break;

            case com.jcraft.jzlib.JZlib.Z_STREAM_END:
              // The compressed stream has ended
              break inflateLoop;

            default:
              throw new IOException("problem with zlib: " + Integer.toHexString(status));
          }
        }
      }
    }
  }

  private int refillInput(ByteSource source) {
    // Pull as much bytes from the source as possible
    input.clear();
    while (source.hasRemaining() && input.hasRemaining()) {
      source.pullBytes(input);
    }
    return input.flip().limit();
  }

  @Override
  public synchronized void close() {
    synchronized (inflater) {
      inflater.end();
    }
  }
}
```

## Registering a Factory

Here's a `BodyDecoder.Factory` for `gzip` using our jzlib decoder.

```java
public static final class MyDecoderFactory implements BodyDecoder.Factory {
  @Override
  public String encoding() {
    return "gzip";
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream) {
    return new AsyncBodyDecoder<>(
        new JZlibDecoder("gzip", com.jcraft.jzlib.JZlib.WrapperType.GZIP), downstream);
  }

  @Override
  public <T> BodyDecoder<T> create(BodySubscriber<T> downstream, Executor executor) {
    return new AsyncBodyDecoder<>(
        new JZlibDecoder("gzip", com.jcraft.jzlib.JZlib.WrapperType.GZIP), downstream);
  }
}
```

The next step is to declare your factory as a service-provider. For instance, here's an appropriate
`provides...with` declaration to put in `module-info.java` if your application uses Java modules.

```java
module my.module {
  ...

  provides BodyDecoder.Factory with MyDecoderFactory;
}
```

[gzip-rfc]: https://tools.ietf.org/html/rfc1952
[methanol-brotli]: https://github.com/mizosoft/methanol/tree/master/methanol-brotli
[brotli]: https://github.com/google/brotli
[jzlib]: https://www.jcraft.com/jzlib/
[morebodyhandlers_decoding_javadoc]: https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/MoreBodyHandlers.html#decoding(java.net.http.HttpResponse.BodyHandler)
[bodydecoder_javadoc]: https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/BodyDecoder.html
