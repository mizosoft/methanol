# Methanol

A lightweight library that complements `java.net.http` for a better HTTP experience.

[![CI status](https://github.com/mizosoft/methanol/workflows/CI/badge.svg)](https://github.com/mizosoft/methanol/actions)
[![Coverage Status](https://coveralls.io/repos/github/mizosoft/methanol/badge.svg?branch=master)](https://coveralls.io/github/mizosoft/methanol?branch=master)
[![Maven Central](https://img.shields.io/maven-central/v/com.github.mizosoft.methanol/methanol.svg?label=Maven%20Central)](https://search.maven.org/search?q=g:%22com.github.mizosoft.methanol%22%20AND%20a:%22methanol%22)

## Overview

***Methanol*** provides useful lightweight HTTP extensions aimed at making it much easier to work
with `java.net.http`. Applications using Java's non-blocking HTTP client shall find it more robust
and easier to use with Methanol.

### Features

* Automatic response decompression.
* Special `BodyPublisher` implementations for form submission.
* Extensible object conversion mechanism, with support for JSON, XML and Protocol Buffers out of the box.
* Enhanced `HttpClient` with interceptors, request decoration and async `Publisher<HttpResponse<T>>` dispatches.
* Tracking the progress of upload or download operations.
* Additional `BodyPublisher`, `BodySubscriber` and `BodyHandler` implementations.

## Installation

### Gradle

```gradle
dependencies {
  implementation 'com.github.mizosoft.methanol:methanol:1.2.0'
}
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>com.github.mizosoft.methanol</groupId>
    <artifactId>methanol</artifactId>
    <version>1.2.0</version>
  </dependency>
</dependencies>
```

## Documentation

* [User Guide](UserGuide.md)
* [Javadoc](https://mizosoft.github.io/methanol/1.x/doc/)

## Examples

### Response decompression

The HTTP client has no native decompression support. Methanol ameliorates this in a flexible and
reactive-friendly way so that you don't have to use blocking streams like `GZIPInputStream`.

```java
final HttpClient client = HttpClient.newHttpClient();

<T> T get(String url, BodyHandler<T> handler) throws IOException, InterruptedException {
  MutableRequest request = MutableRequest.GET(url)
      .header("Accept-Encoding", "gzip");
  HttpResponse<T> response = client.send(request, MoreBodyHandlers.decoding(handler));
  int statusCode = response.statusCode();
  if (statusCode < 200 || statusCode > 299) {
    throw new IOException("failed response: " + statusCode);
  }

  return response.body();
}
```

### Object conversion

Methanol provides a  flexible mechanism for dynamically converting objects to or from request
or response bodies respectively. This example interacts with GitHub's JSON API. It is assumed
you have [methanol-gson](methanol-gson) or [methanol-jackson](methanol-jackson) installed.

```java
final Methanol client = Methanol.newBuilder()
    .baseUri("https://api.github.com")
    .defaultHeader("Accept", "application/vnd.github.v3+json")
    .build();

GitHubUser getUser(String name) throws IOException, InterruptedException {
  MutableRequest request = MutableRequest.GET("/users/" + name);
  HttpResponse<GitHubUser> response =
      client.send(request, MoreBodyHandlers.ofObject(GitHubUser.class));

  return response.body();
}

// For complex types, use a TypeRef
List<GitHubUser> getUserFollowers(String userName) throws IOException, InterruptedException {
  MutableRequest request = MutableRequest.GET("/users/" + userName + "/followers");
  HttpResponse<List<GitHubUser>> response =
      client.send(request, MoreBodyHandlers.ofObject(new TypeRef<List<GitHubUser>>() {}));

  return response.body();
}

String renderMarkdown(RenderRequest renderRequest) throws IOException, InterruptedException {
  BodyPublisher requestBody = MoreBodyPublishers.ofObject(renderRequest, MediaType.APPLICATION_JSON);
  // No need to set Content-Type header!
  MutableRequest request = MutableRequest.POST("/markdown", requestBody)
      .header("Accept", "text/html");
  HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

  return response.body();
}

static class GitHubUser {
  public String login;
  public long id;
  public String bio;
  // other fields omitted
}

static class RenderRequest {
  public String text, mode, context;
}
```

### Form bodies

You can use `FormBodyPublisher` for submitting URL-encoded forms. In this example, an article is
downloaded from Wikipedia using a provided search query.

```java
final Methanol client = Methanol.newBuilder()
    .baseUri("https://en.wikipedia.org")
    .followRedirects(HttpClient.Redirect.NORMAL)
    .build();

Path downloadArticle(String title) throws IOException, InterruptedException {
  FormBodyPublisher searchQuery = FormBodyPublisher.newBuilder()
      .query("search", title)
      .build();
  MutableRequest request = MutableRequest.POST("/wiki/Main_Page", searchQuery);
  HttpResponse<Path> response =
      client.send(request, BodyHandlers.ofFile(Path.of(title + ".html")));

  return response.body();
}
```

### Multipart bodies

The library also provides flexible support for multipart. In this example, a multipart body is used
to upload an image to [imgur](https://imgur.com).

```java
final Methanol client = Methanol.newBuilder()
    .baseUri("https://api.imgur.com/3/")
    .defaultHeader("Authorization", "Client-ID " + System.getenv("IMGUR_CLIENT_ID")) // substitute with your client ID
    .build();

URI uploadToImgur(String title, Path image) throws IOException, InterruptedException {
  MultipartBodyPublisher imageUpload = MultipartBodyPublisher.newBuilder()
      .textPart("title", title)
      .filePart("image", image)
      .build();
  MutableRequest request = MutableRequest.POST("upload", imageUpload);
  HttpResponse<Reader> response = client.send(request, MoreBodyHandlers.ofReader());

  try (Reader reader = response.body()) {
    String link = com.google.gson.JsonParser.parseReader(reader)
        .getAsJsonObject()
        .getAsJsonObject("data")
        .get("link")
        .getAsString();

    return URI.create(link);
  }
}
```

### Reactive request dispatches

For a truly reactive experience, one might want to dispatch async requests as
`Publisher<HttpResponse<T>>` sources. `Methanol` client complements `sendAsync` with `exchange` for
such a task. This example assumes you have [methanol-jackson-flux](methanol-jackson-flux) installed.

```java
final Methanol client = Methanol.newBuilder()
    .baseUri("https://api.github.com")
    .defaultHeader("Accept", "application/vnd.github.v3+json")
    .build();

Flux<GitHubUser> getContributors(String repo) {
  MutableRequest request = MutableRequest.GET("/repos/" + repo + "/contributors");
  Publisher<HttpResponse<Flux<GitHubUser>>> publisher =
      client.exchange(request, MoreBodyHandlers.ofObject(new TypeRef<Flux<GitHubUser>>() {}));
  
  return JdkFlowAdapter.flowPublisherToFlux(publisher).flatMap(HttpResponse::body);
}
```

#### Push promises

This also works well with push-promise enabled servers. Here, the publisher streams a non-ordered
sequence including the main response along with other resources pushed by the server.

```java
Methanol client = Methanol.create(); // default Version is HTTP_2
MutableRequest request = MutableRequest.GET("https://http2.golang.org/serverpush");
Publisher<HttpResponse<Path>> publisher =
    client.exchange(
        request,
        BodyHandlers.ofFile(Path.of("index.html")),
        promise -> BodyHandlers.ofFile(Path.of(promise.uri().getPath()).getFileName()));
JdkFlowAdapter.flowPublisherToFlux(publisher)
    .filter(res -> res.statusCode() == 200)
    .map(HttpResponse::body)
    .subscribe(System.out::println);
```

### Tracking progress

A responsive application needs a method to provide progression feedback for long-running tasks.
`ProgressTracker` comes in handy in such case. This example logs progress events of a large
file download.

```java
final HttpClient client = HttpClient.newHttpClient();
final ProgressTracker tracker =
    ProgressTracker.newBuilder()
        .timePassedThreshold(Duration.ofSeconds(1))
        .build();

Path download() throws IOException, InterruptedException {
  MutableRequest request = MutableRequest.GET("https://norvig.com/big.txt");
  HttpResponse<Path> response =
      client.send(request, tracker.tracking(BodyHandlers.ofFile(Path.of("big.txt")), this::logProgress));

  return response.body();
}

void logProgress(Progress progress) {
  var record = "Downloaded: " + progress.totalBytesTransferred() + " bytes";

  // log percentage if possible
  if (progress.determinate()) {
    record += " (" + round(100.d * progress.value()) + "%)";
  }

  // log download speed
  long millis = progress.timePassed().toMillis();
  if (millis > 0L) {
    float bytesPerSecond = (1.f * progress.bytesTransferred() / millis);
    record += " (" + round(bytesPerSecond * (1000.f / 1024)) + " KB/s)";
  }

  System.out.println(record);
}

static float round(double value) {
  return Math.round(100.f * value) / 100.f;
}
```

## License

[MIT](https://choosealicense.com/licenses/mit/)
