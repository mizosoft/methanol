# methanol-kotlin

Kotlin extensions for Methanol, which include:

- A DSL for HTTP requests.
- Adapters for [Kotlin Serialization](https://kotlinlang.org/docs/serialization.html).

## Installation

### Gradle

```kotlin
implementation("com.github.mizosoft.methanol:methanol-kotlin:1.8.4")
```

## Usage

Most types and functions in this module are defined as type aliases and extension functions to core Methanol & JDK HTTP client libraries.
They have a different, more Kotlin-like feel, however. The best way to get familiar is to go through the examples.
There's also the [KDocs](https://mizosoft.github.io/methanol/api/latest/methanol.kotlin/com.github.mizosoft.methanol.kotlin/index.html)
for a comprehensive list of what is provided. For advanced usage, it's a good idea to be familiar with the Java libraries this module extends.

Almost everything in this module is configured with lambda expressions that are resolved against a particular [Spec](https://mizosoft.github.io/methanol/api/latest/methanol.kotlin/com.github.mizosoft.methanol.kotlin/-spec/index.html?query=annotation%20class%20Spec).
Look up the KDocs/source of a spec to know all what it can configure.

Runnable code examples are linked at the end of each section. If you're going to copy from the snippets, add these imports:

```kotlin
import com.github.mizosoft.methanol.*
import com.github.mizosoft.methanol.kotlin.*
```

### Get & Post String

Let's get started by creating our client.

```kotlin
val client = Client {
  adapterCodec {
    basic()
  }
}
```

Here, we're configuring a [ClientSpec](https://mizosoft.github.io/methanol/api/latest/methanol.kotlin/com.github.mizosoft.methanol.kotlin/-client-spec/index.html). The only thing we'll configure for now is the client's [`AdapterCodec`](https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/AdapterCodec.html),
which tells it how to map high level types to & from HTTP bodies. `basic()` is good enough for, well, basic types, like `String` & `InputStream`.
Trace through [`basicEncoder`](https://mizosoft.github.io/methanol/api/latest/methanol.kotlin/com.github.mizosoft.methanol.kotlin/-adapter-codec-spec/basic-encoder.html) & [`basicDecoder`](https://mizosoft.github.io/methanol/api/latest/methanol.kotlin/com.github.mizosoft.methanol.kotlin/-adapter-codec-spec/basic-decoder.html) for all the supported basic types.

#### Get String

Now let's GET a string from a URL.

```kotlin
suspend fun runGet() {
  val response = client.get<String>("https://www.cl.cam.ac.uk/~mgk25/ucs/examples/UTF-8-demo.txt")
  require(response.isSuccessful()) { "Unsuccessful response: $response - ${response.body()}" }
  println(response.body())
}
```

Known HTTP verbs have corresponding client-defined functions, like the `client::get` above. Each HTTP verb function returns
a `Response` whose `Response::body` is already converted into the specified type. These functions are suspending, meaning they run as part of a [coroutine](https://kotlinlang.org/docs/coroutines-overview.html).

#### Post String

HTTP verb functions take an optional request configuration block next to the URI. We can use it to specify the body of body-bearing requests.

```kotlin
suspend fun runPost() {
  val response: Response<String> = client.post("https://api.github.com/markdown/raw") {
    body(
      """
        > He who has a ***why*** can bear almost any ***how***.
        >  - Friedrich Nietzsche
      """.trimIndent(),
      MediaType.TEXT_MARKDOWN
    )
  }
  require(response.isSuccessful()) { "Unsuccessful response: $response - ${response.body()}" }
  println(response.body())
}
```

Next to what we want to send, we pass `body` a [`MediaType`](https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/MediaType.html) signifying the desired mapping format. This `MediaType` becomes the request's `Content-Type`.

Note that we specified the response body type on the left of `client.post(...)`. We could have written `val response = client.post<String>(...)`,
but that can hurt readability since `String` in this expression is what we're getting, not what we're posting; the latter is defined by `body`. You can use either way, though.

[Runnable Example](https://github.com/mizosoft/methanol/blob/master/methanol-samples/kotlin/src/main/kotlin/com/github/mizosoft/methanol/samples/kotlin/GetPostString.kt).

### Get & Post JSON

Let's get more sophisticated. The basic adapter is nice, but it ain't much. We can make the client understand JSON through Kotlin Serialization.
You'll first need to apply the serialization plugin in your build script & pull in `kotlinx-serialization-json` as specified [here](https://kotlinlang.org/docs/serialization.html#example-json-serialization).

Now we redefine our client.

```kotlin
val client = Client {
  baseUri("https://api.github.com/")
  defaultHeaders {
    "Accept" to "application/vnd.github+json"
    "X-GitHub-Api-Version" to "2022-11-28"
  }
  adapterCodec {
    basic()
    +KotlinAdapter.Encoder(
      Json, MediaType.APPLICATION_JSON
    )
    +KotlinAdapter.Decoder(Json {
      ignoreUnknownKeys = true // For brevity, we'll skip most fields.
    }, MediaType.APPLICATION_JSON)
  }
}
```

This time our client has more configuration, most of which is self-descriptive. The interesting part is how we configure the `AdapterCodec`.
We add (hence the `+`) an encoder & a decoder that use `kotlinx-serialization-json`'s [`Json`](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-json/kotlinx.serialization.json/-json/).
`KotlinAdapter.Encoder` & `KotlinAdapter.Decoder` are pluggable, and hence can work with all the [supported formats](https://kotlinlang.org/docs/serialization.html#formats).
You'll just need to pass the desired [SerialFormat](https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-core/kotlinx.serialization/-serial-format/), and one or more `MediaType`s signifying that format.

Note that we keep the basic adapter to still be able to send & receive basic types. `AdapterCodec` will figure out which adapter to use.
You can also add adapters for other formats, say `application/protobuf`. The first adapter (in addition order) that can handle
the object type based on the given `MediaType` is selected.

#### Get JSON

Now it's a matter of type specification.

```kotlin
@Serializable
data class Repository(
  val description: String,
  @SerialName("full_name") val fullName: String
)

suspend fun runGet() {
  val response = client.get<List<Repository>>("users/mizosoft/starred?per_page=10")
  response.body().forEach { repo ->
    println("${repo.fullName}\n\t${repo.description}")
  }
}
```

#### Post JSON

For posts, specify the `MediaType` next to your payload.

```kotlin
@Serializable
data class Markdown(
  val text: String,
  val context: String,
  val mode: String
)

suspend fun runPost() {
  val response: Response<String> = client.post("markdown") {
    body(
      Markdown(
        "this code very fast: #437",
        "torvalds/linux",
        "gfm"
      ),
      MediaType.APPLICATION_JSON
    )
  }
  println(response.body())
}
```

[Runnable Example]([Runnable Example](https://github.com/mizosoft/methanol/blob/master/methanol-samples/kotlin/src/main/kotlin/com/github/mizosoft/methanol/samples/kotlin/GetPostJson.kt).

### Multipart & Forms

Now let's say we want to upload some cat memes to [Imgur](https://imgur.com/) using their [API](https://api.imgur.com/endpoints/image).

#### Multipart Bodies

`multipart/format-data` bodies are perfect for that task. We'll be making use of the JSON decoder from above to extract the image link.

```kotlin
// You can get your own clien ID here: https://api.imgur.com/oauth2/addclient.
val imgurClientId = System.getProperty("imgur.client.id")

val client = Client {
  baseUri("https://api.imgur.com/3/")
  defaultHeaders {
    "Authorization" to "Client-ID $imgurClientId"
  }

  adapterCodec {
    basic()
    +KotlinAdapter.Decoder(Json {
      ignoreUnknownKeys = true
    }, MediaType.APPLICATION_JSON)
  }
}

@Serializable
data class ImgurResponse<T>(val status: Int, val success: Boolean, val data: T?)

@Serializable
data class Image(val link: String)

suspend fun multipartUpload() {
  val response: Response<ImgurResponse<Image>> = client.post("image") {
    multipartBody {
      "image" to Path.of("images/popcat.gif") // File's Content-Type will be looked-up automatically.
      "title" to "PopCat"
      "description" to "A cat that pops"
    }
  }
  require(response.body().success) {
    "Unsuccessful response: $response - ${response.body()}"
  }
  println("Uploaded: ${response.body().data!!.link}")
}
```

The [`multipartBody`](https://mizosoft.github.io/methanol/api/latest/methanol.kotlin/com.github.mizosoft.methanol.kotlin/-request-body-spec/multipart-body.html)
block makes it easy to configure `multipart/form-data` bodies as key-value pairs. It can also be used to configure any kind of `multipart/*` body.

#### Form Bodies

It turns out that Imgur's upload API also accepts `application/x-www-form-urlencoded` submissions, which may result in more efficient uploads if we
have the image bytes in memory. [`formBody`](https://mizosoft.github.io/methanol/api/latest/methanol.kotlin/com.github.mizosoft.methanol.kotlin/-request-body-spec/form-body.html) is here to help.

```kotlin
@OptIn(ExperimentalEncodingApi::class) // In order to use Kotlin's Base64.
suspend fun formUpload() {
  val response: Response<ImgurResponse<Image>> = client.post("image") {
    formBody {
      "image" to Base64.Default.encode(Path.of("images/popcat.gif").readBytes())
      "type" to "base64"
      "title" to "PopCat"
      "description" to "A cat that pops"
    }
  }
  require(response.body().success) {
    "Unsuccessful response: $response - ${response.body()}"
  }
  println("Uploaded: ${response.body().data!!.link}")
}
```

When using `multipartBody` or `formBody`, the request's `Content-Type` will be set for you.

[Runnable Example](https://github.com/mizosoft/methanol/blob/master/methanol-samples/kotlin/src/main/kotlin/com/github/mizosoft/methanol/samples/kotlin/MultipartAndFormUploads.kt).

### Caching

Methanol provides an HTTP caching solution, which supports disk, memory & redis storage backends.

```kotlin
val client = Client {
  userAgent("Chuck Norris")
  cache {
    onDisk(Path.of(".cache"), 500 * 1024 * 1024) // Occupy at most 500Mb on disk.
  }
}
```

The cache will be used automatically as you use the client. You can communicate with it using request's `Cache-Control`.

```kotlin
suspend fun run() {
  val response =
    client.get("https://i.imgur.com/V79ulbT.gif", BodyHandlers.ofFile(Path.of("popcat.gif"))) {
      cacheControl {
        maxAge(5.seconds) // Override server's max-age.
      }
    } as CacheAwareResponse<Path>
  println(
    "$response - ${response.cacheStatus()} (Cached for ${response.headers()["Age"].firstOrNull() ?: -1} seconds)"
  )
}
```

Run this example multiple times within 5 seconds and then apart.

You can also set up a chain of caches, typically in the order of decreasing locality.

```kotlin
val redisUrl = System.getProperty("redis.url")

val client = Client {
  userAgent("Chuck Norris")
  cacheChain {
    +Cache {
      inMemory(100 * 1024 * 1024) // Occupy at most 100Mb in memory.
    }
    +Cache {
      onDisk(500 * 1024 * 204) // Occupy at most 500Mb on disk.
    }
  }
}
```

Here, we're calling the [`Cache`](https://mizosoft.github.io/methanol/api/latest/methanol.kotlin/com.github.mizosoft.methanol.kotlin/-cache.html)
constructor, and prepend a `+` to add it to the chain. The chain is invoked in the order of addition.

In case of a single cache or a cache chain, it's always a good practice to close it when you're done.

```kotlin
client.caches().close()
```

You can learn more about caching [here](https://mizosoft.github.io/methanol/caching/).

[Runnable Example](https://github.com/mizosoft/methanol/blob/master/methanol-samples/kotlin/src/main/kotlin/com/github/mizosoft/methanol/samples/kotlin/CachingClient.kt).

### Interceptors

So far, if we wanted to validate the response, we had to do so each time we get it. We may also want to log the request/response exchange, or send some metrics to a monitoring framework. This is a perfect usage for interceptors.

```kotlin
object LoggingInterceptor : Interceptor {
  val logger: System.Logger = System.getLogger(LoggingInterceptor::class.simpleName)
  val requestIdGenerator = AtomicInteger()

  override suspend fun <T> intercept(
    request: Request,
    chain: Interceptor.Chain<T>
  ): Response<T> {
    val requestId = requestIdGenerator.getAndIncrement()
    val start = System.currentTimeMillis()
    logger.log(Level.INFO) {
      "$requestId: sending $request \n${request.headers().toHttpString()}"
    }

    return chain.forward(request).also { response ->
      logger.log(Level.INFO) {
        "$requestId: received $response in ${(System.currentTimeMillis() - start).milliseconds} \n" +
            request.headers().toHttpString()
      }
      require(response.isSuccessful()) { "Unsuccessful response: $response" }
    }
  }
}

val client = Client {
  interceptors {
    +LoggingInterceptor
  }

  userAgent("Dave Bautista")
  adapterCodec {
    basic()
  }
}

suspend fun run() {
  client.get<Unit>("https://httpbin.org/gzip") {
    headers {
      "Accept" to MediaType.APPLICATION_OCTET_STREAM.toString()
    }
  }
}
```

More than one interceptor can be added. Together, they form a chain that is invoked in the order of addition.

Running this code gives:

```
Dec 06, 2024 1:10:47 PM com.github.mizosoft.methanol.samples.kotlin.ClientInterceptor$LoggingInterceptor intercept
INFO: 0: sending https://httpbin.org/gzip GET
Accept: application/octet-stream

Dec 06, 2024 1:10:48 PM com.github.mizosoft.methanol.samples.kotlin.ClientInterceptor$LoggingInterceptor intercept
INFO: 0: received (GET https://httpbin.org/gzip) 200 in 1.631s
:status: 200
access-control-allow-credentials: true
access-control-allow-origin: *
content-type: application/json
date: Fri, 06 Dec 2024 11:10:48 GMT
server: gunicorn/19.9.0
```

If you squint, you'll notice that the request headers don't contain a `User-Agent`, although we've configured the client with one.
Additionally, the response lacks typical headers like `Content-Length` & `Content-Encoding`.

Let's instead add `LoggingInterceptor` as what we'll call a backend interceptor.

```kotlin
val client = Client {
  backendInterceptors {
    +LoggingInterceptor
  }

  userAgent("Arnold Schwarzenegger")
  adapterCodec {
    basic()
  }
}
```

Running this code gives:

```
Dec 06, 2024 2:02:55 PM com.github.mizosoft.methanol.samples.kotlin.ClientInterceptor$LoggingInterceptor intercept
INFO: 0: sending https://httpbin.org/gzip GET
Accept: application/octet-stream
Accept-Encoding: deflate, gzip
User-Agent: Dave Bautista

Dec 06, 2024 2:02:56 PM com.github.mizosoft.methanol.samples.kotlin.ClientInterceptor$LoggingInterceptor intercept
INFO: 0: received (GET https://httpbin.org/gzip) 200 in 1.602s
:status: 200
access-control-allow-credentials: true
access-control-allow-origin: *
content-encoding: gzip
content-length: 241
content-type: application/json
date: Fri, 06 Dec 2024 12:02:56 GMT
server: gunicorn/19.9.0
```

Now we can see a `User-Agent`, an `Accept-Encoding` added implicitly by the client, and the typical response headers.

Client interceptors (which we added first within the `interceptors` block) see the request just as given to the client,
and the response after the client does some changes (e.g. decompression). Backend interceptors see the request just before sending it,
and the response just as received.

Note that Kotlin's [`Interceptor`](https://mizosoft.github.io/methanol/api/latest/methanol.kotlin/com.github.mizosoft.methanol.kotlin/-interceptor/index.html?query=interface%20Interceptor)
is a different interface from core library's [`Methanol.Interceptor`](https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/Methanol.Interceptor.html).
This is so that Kotlin's interceptors can support coroutines; they are functionally the same, however.
You can learn more about interceptors [here](https://mizosoft.github.io/methanol/interceptors/#client-interceptors).

[Runnable Example](https://github.com/mizosoft/methanol/blob/master/methanol-samples/kotlin/src/main/kotlin/com/github/mizosoft/methanol/samples/kotlin/Interceptors.kt).

#### Retrying Requests

Methanol provides an [interceptor implementation](https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/RetryInterceptor.html) for retrying requests based on configurable retry conditions. The Kotlin DSL for configuring the interceptor is derived from [RetryInterceptor.Builder](https://mizosoft.github.io/methanol/api/latest/methanol/com/github/mizosoft/methanol/RetryInterceptor.Builder.html).
You can configure the interceptor to retry on certain exceptions, status codes, or generic response predicates. For instance, this is
an interceptor that retries on timeouts and server errors at most 3 times, backing off exponentially on each retry.

```kotlin
val client = Client {
  adapterCodec {
    basic()
  }
  connectTimeout(5.seconds)
  requestTimeout(10.seconds)
  interceptors {
    +RetryInterceptor {
      maxRetries(5) // Default is 5
      onException<ConnectException, HttpTimeoutException>()
      onStatus(HttpStatus::isServerError)
      backoff(BackoffStrategy.exponential(100.milliseconds, 15.seconds).withJitter())

      // Fail when all retry attempts are consumed instead of returning response/exception as-is.
      throwOnExhaustion()

      // Only retry selected hosts. Remove if you want to retry all requests.
      onlyIf { request -> request.uri().host.contains("example") }
    }
  }
}

val response = client.get<String>("https://internal.example.com")
```

For more info, see [Retrying Requests](https://mizosoft.github.io/methanol/retrying_requests//).

#### Coroutines

HTTP verb functions are suspending, which is also the case with `Interceptor::intercept`. In fact, the entire interceptor chain
along with the ultimate HTTP call all share the same [coroutine context](https://kotlinlang.org/docs/coroutine-context-and-dispatchers.html). This implies that cancelling the HTTP call also cancels whatever some interceptor is doing.

```kotlin
val client = Client {
  interceptors {
    +object : Interceptor {
      override suspend fun <T> intercept(
        request: Request,
        chain: Interceptor.Chain<T>
      ): Response<T> {
        println("Invoking interceptor with ${coroutineContext[CoroutineName]}")
        return try {
          delay(1000L)
          chain.forward(request)
        } catch (e: CancellationException) {
          println("Cancelled interceptor")
          throw e
        }
      }
    }
  }

  adapterCodec {
    basic()
  }
}

fun run() {
  runBlocking(CoroutineName("MyCoroutine")) {
    val job = launch {
      client.get<String>("https://httpbin.org/get")
    }
    delay(500)
    job.cancel()
  }
}
```

Running the code gives the following output.

```
Calling interceptor with CoroutineName(MyCoroutine)
Cancelled interceptor
```
