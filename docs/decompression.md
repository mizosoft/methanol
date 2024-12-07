# Response Decompression

One caveat concerning Java's HTTP client is the lack of support for automatic response decompression.
A workaround is to use an available `InputStream` decompressor (e.g. `GZIPInputStream`) that matches response's `Content-Encoding`.
However, such approach is invasive as it forces you to deal with `InputStreams`.

The straightforward and recommended solution is to use [Methanol's HTTP client](methanol_httpclient.md), which gives you transparent response decompression for `gzip` & `deflate` out of the box.

```java
final Methanol client = Methanol.create();

<T> HttpResponse<T> get(String url, BodyHandler<T> bodyHandler) throws IOException, InterruptedException {
  // No need to worry about adding Accept-Encoding and decompressing the response, the client does that for you!
  return client.send(MutableRequest.GET(url), bodyHandler);
}
```

Read on if you're interested in knowing how that's accomplished, or you want to extend decompression support.

## Decoding BodyHandler

The entry point to response body decompression is [`MoreBodyHandlers::decoding`][morebodyhandlers_decoding_javadoc].
This method takes your desired `BodyHandler` and gives you one that decompresses the response body as your handler's `BodySubscriber` receives it.

```java
var response = client.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
```

It doesn't matter which `BodyHandler` you're using; you can have whatever response body type you want.

## BodyDecoder

A [`BodyDecoder`][bodydecoder_javadoc] is a `BodySubscriber` with the added semantics of a `Flow.Processor`.
It intercepts the flow of bytes on its way down from the HTTP client, decoding each `List<ByteBuffer>` individually.
The decoded bytes are forwarded to a downstream `BodySubscriber`, which converts them into the desired response body.

### BodyDecoder.Factory

A `BodyDecoder.Factory` associates itself with a defined encoding that's suitable as a `Content-Encoding` directive.
It creates `BodyDecoder` instances that forward the decompressed response body to a downstream `BodySubscriber`.

Factories are installed as service-providers in the manner specified by Java's `ServiceLoader`.
The handler returned by `MoreBodyHandlers::decoding` looks up a factory matching the response's `Content-Encoding` to wrap user's `BodySubscriber`.
If no such factory is found, an `UnsupportedOperationException` is thrown.

## Supported Encodings

The core module has support for `gzip` & `deflate` out of the box. There's also a separate [module][methanol-brotli] for [brotli].

## Extending decompression support

Adding support for more encodings or overriding supported ones is a matter of writing a `BodyDecoder` implementation and providing a corresponding factory.
However, implementing the decoder's `Flow.Publisher` semantics can be tricky. Instead, implement an `AsyncDecoder` and wrap it in an `AsyncBodyDecoder`, so
you're only concerned with the decompression logic.

### Writing an AsyncDecoder

Decoding is done as a number of `decode(source, sink)` rounds finalized by one final round, each with the currently available input.
After the final round, your `AsyncDecoder` must've completely exhausted its source.
Here's a decoder implementation that uses [jzlib] for `gzip` & `deflate` decompression.

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
        refillInput(source);
        inflater.setNextInIndex(0);
        inflater.setAvailIn(input.limit());

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

  private void refillInput(ByteSource source) {
    input.clear();
    source.pullBytes(input);
    input.flip();
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

The next step is to declare your factory as a service-provider. If your application uses Java modules,
you'd have a declaration like the following in your `module-info.java`.

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
