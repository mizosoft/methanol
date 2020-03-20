Note: This project is a WIP

# Methanol
[![CI status](https://github.com/mizosoft/methanol/workflows/CI/badge.svg)](https://github.com/mizosoft/methanol/actions)
[![Coverage Status](https://coveralls.io/repos/github/mizosoft/methanol/badge.svg)](https://coveralls.io/github/mizosoft/methanol)
A set of useful extensions for the Java HTTP client.

## Overview

As of release 11, Java had it's own standard HTTP client, which replaced the good old
`HttpURLConnection`. The client is easy to use due to it's simple and modern API. It leverages the
Flow API introduced in Java 9, making it asynchronous by nature. However, it lacks some features 
that are essential for any application using HTTP. ***Methanol*** aims to provided these features 
via extensions to the client's API.

Extensions include:
  - Automatic decompression for response bodies.
  - Special `BodyPublisher` implementations for form submission.
  - Extensible object conversion mechanism.
  - Additional `BodySubscriber` and `BodyHandler` implementations.

## Examples

### Decoding a response

Automatically decode the response body for a downstream `BodyHandler`.
```java
var client = HttpClient.newHttpClient();
var request = HttpRequest.newBuilder(URI.create("..."))
    .header("Accept-Encoding", "gzip, deflate")
    .build();
HttpResponse<String> response = client.send(request, MoreBodyHandlers.decoding(BodyHandlers.ofString()));
...
```
### Submitting a form

POST a form in either `application/x-www-form-urlencoded` or `multipart/form-data` formats.

```java
var client = HttpClient.newHttpClient();

// Use a url-encoded body
var fromBody = FormBodyPublisher.newBuilder()
    .query("name", "Elon Musk")
    .query("status", "Being awesome")
    .build();

// Use multipart
var formBody = MultipartBodyPublisher.newBuilder()
    .textPart("title", "Fresh Meme (but is it?)")
    .filePart("image", Path.of("memes/baby_yoda.png"), MediaType.of("image", "png"))
    .formPart("another_field", BodyPublishers.ofByteArray(...)) // Any BodyPublisher is supported
    .build();

var request = HttpRequest.newBuilder(URI.create("..."))
    .POST(formBody)
    .header("Content-Type", formBody.mediaType().toString())
    .build();
HttpResponse<String> response  = client.send(request, BodyHandlers.ofString());
```

### Converting the response body into a Java object
Decode the response body into a Java object (e.g. `GitHubUser`) using an installed `BodyAdapter.Decoder` for JSON.

```java
var client = HttpClient.newHttpClient();
var request = HttpRequest.newBuilder(URI.create("https://api.github.com/users/mizosoft"))
    .GET()
    .build();
HttpResponse<GitHubUser> response = client.send(request, MoreBodyHandlers.ofObject(GitHubUser.class));
```

# LICENSE
```
MIT License
    
Copyright (c) 2019 Moataz Abdelnasser

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
