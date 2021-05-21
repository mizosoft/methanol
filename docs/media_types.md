# Mime

[Media types][mime-types-mdn] are the web's notion for file extensions. They're typically present in
requests and responses as `Content-Type` directives. Methanol's `MediaType` greatly facilitates the
representation and manipulation of media types.
    
## MediaType

You can create a `MediaType` from its individual components or parse one from a `Content-Type` string.

=== "MediaType::of"

    ```java
    var applicationJsonUtf8 = MediaType.of("application", "json", Map.of("charset", "UTF-8")); 

    assertEquals("application", applicationJsonUtf8.type());
    assertEquals("json", applicationJsonUtf8.subtype());
    assertEquals("utf-8", applicationJsonUtf8.parameters().get("charset"));
    assertEquals(Optional.of(StandardCharsets.UTF_8), applicationJsonUtf8.charset());
    ```

=== "MediaType::parse"

    ```java
    var applicationJsonUtf8 = MediaType.parse("application/json; charset=UTF-8");

    assertEquals("application", applicationJsonUtf8.type());
    assertEquals("json", applicationJsonUtf8.subtype());
    assertEquals("utf-8", applicationJsonUtf8.parameters().get("charset"));
    assertEquals(Optional.of(StandardCharsets.UTF_8), applicationJsonUtf8.charset());
    ```
 
### Media Ranges

A `MediaType` also defines a [media range][media-ranges-rfc] to which one or more media types belong,
including itself.

```java
var anyTextType = MediaType.parse("text/*");
var textHtml = MediaType.parse("text/html");
var applicationJson = MediaType.parse("application/json");

assertTrue(anyTextType.hasWildcard());
assertTrue(anyTextType.includes(textHtml));
assertFalse(anyTextType.includes(applicationJson));
assertTrue(anyTextType.isCompatibleWith(textHtml));
assertTrue(textHtml.isCompatibleWith(anyTextType));
```

!!! tip
    `MediaType` has static definitions for the most popular media types & ranges. None of them,
    however, defines a `charset` parameter. You can use `MediaType::withCharset` to derive media
    types with charsets from the statically defined ones.

    ```java
    static final MediaType APPLICATION_JSON_UTF8 = 
        MediaType.APPLICATION_JSON.withCharset(StandardCharsets.UTF_8);
    ```

## MimeBodyPublisher

`MimeBodyPublisher` is a mixin-style interface that associates a `MediaType` with a `BodyPublisher`. 
It's recognized by `Methanol` and [multipart bodies](multipart_and_forms.md#multipart-bodies) in that
it gets the appropriate `Content-Type` header implicitly added.

You can adapt an arbitrary `BodyPublisher` into a `MimeBodyPublisher`. Here's a factory method that
creates `MimeBodyPublihers` for files. The file's media type is probed from the system, falling
back to `application/octet-stream` if that doesn't work. 

```java
static MimeBodyPublisher ofMimeFile(Path file) throws FileNotFoundException {
  MediaType mediaType = null;
  try {
    var contentType = Files.probeContentType(file);
    if (contentType != null) {
      mediaType = MediaType.parse(contentType);
    }
  } catch (IOException ignored) {
  }

  return MoreBodyPublishers.ofMediaType(
      BodyPublishers.ofFile(file),
      requireNonNullElse(mediaType, MediaType.APPLICATION_OCTET_STREAM));
}

final Methanol client = Methanol.create();

<T> HttpResponse<T> post(String url, Path file, BodyHandler<T> handler)
    throws IOException, InterruptedException {
    
  // Request's Content-Type is implicitly added
  return client.send(MutableRequest.POST(url, ofMimeFile(file)), handler);
}
```

[mime-types-mdn]: https://developer.mozilla.org/en-US/docs/Web/HTTP/Basics_of_HTTP/MIME_types
[media-ranges-rfc]: https://tools.ietf.org/html/rfc7231#section-5.3.2
