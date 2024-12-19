# Multipart & Forms
  
Methanol has special `BodyPublisher` implementations for multipart uploads & form submission.

## Multipart Bodies

`MultipartBodyPublisher` implements the multipart format. A multipart body has one or more parts.
Each part has a `BodyPublisher` for its content and `HttpHeaders` that describe it.
`MultipartBodyPublisher.Builder`defaults to `multipart/form-data` if a multipart `MediaType` isn't explicitly specified.
There are special methods for adding parts with a `Content-Disposition: form-data` header generated from a field name and an optional file name.
These are referred to as form parts.

```java
// Substitute with your client ID. Visit https://api.imgur.com/oauth2/addclient to get one.
static final String CLIENT_ID = System.getenv("imgur.client.id"); 

final Methanol client = Methanol.create();

HttpResponse<String> uploadGif() throws IOException, InterruptedException {
  var multipartBody = MultipartBodyPublisher.newBuilder()
      .textPart("title", "Dancing stick bug")
      .filePart("image", Path.of("dancing-stick-bug.gif"), MediaType.IMAGE_GIF)
      .build();
  return client.send(
      MutableRequest.POST("https://api.imgur.com/3/image", multipartBody)
          .header("Authorization", "Client-ID " + CLIENT_ID), 
      BodyHandlers.ofString());
}
```

If `filePart` isn't given a `MediaType`, it asks the system for one using the given `Path`, falling
back to `application/octet-stream` if that doesn't work.

!!! hint
    A part's `Content-Type` is automatically added if it's created with a `MimeBodyPublisher`.

### Generic Form Parts

Use builder's `formPart` to add a form part from an arbitrary `BodyPublisher`. It takes a field name and an optional file name.

```java
// Substitute with your client ID. Visit https://api.imgur.com/oauth2/addclient to get one.
static final String CLIENT_ID = System.getenv("imgur.client.id");

final Methanol client = Methanol.create();

HttpResponse<String> uploadGif() throws IOException, InterruptedException {
  var multipartBody = MultipartBodyPublisher.newBuilder()
      .textPart("title", "Dancing stick bug")
      .formPart(
          "image", title + ".png", MoreBodyPublishers.ofMediaType(imagePart, MediaType.IMAGE_PNG))
      .build();
  return client.send(
      MutableRequest.POST("https://api.imgur.com/3/image", multipartBody)
          .header("Authorization", "Client-ID " + CLIENT_ID),
      BodyHandlers.ofString());
}
```

!!! tip
    Use `MoreBodyPublishers::ofMediaType` to pair an arbitrary `BodyPublisher` with its proper `MediaType`
    if you want a `Content-Type` header to be specified by the part.

## Form Bodies

Use `FormBodyPublisher` to send form data as a set of URL-encoded queries. Data is added as string name-value pairs.

```java
final Methanol client = Methanol.create();

HttpResponse<String> sendQueries(String url, Map<String, String> queries)
    throws IOException, InterruptedException {
  var builder = FormBodyPublisher.newBuilder();
  queries.forEach(builder::query);
  return client.send(MutableRequest.POST(url, builder.build()), BodyHandlers.ofString());
}
```

!!! hint
    Requests with `MultipartBodyPublisher` or `FormBodyPublisher` will have their `Content-Type` header
    added automatically if sent on a `Methanol` client.
