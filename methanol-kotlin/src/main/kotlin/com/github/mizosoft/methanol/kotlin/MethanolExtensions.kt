/*
 * Copyright (c) 2023 Moataz Abdelnasser
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol.kotlin

import com.github.mizosoft.methanol.*
import com.github.mizosoft.methanol.BodyAdapter.Decoder
import com.github.mizosoft.methanol.BodyAdapter.Encoder
import com.github.mizosoft.methanol.Methanol.Interceptor
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder
import com.github.mizosoft.methanol.store.redis.RedisStorageExtension
import io.lettuce.core.RedisURI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient.Redirect
import java.net.http.HttpClient.Version
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandler
import java.nio.charset.Charset
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.time.Duration
import kotlin.time.toJavaDuration

interface HeadersSpec {
  infix fun String.to(value: String)

  infix fun String.to(values: List<String>)

  infix fun String.onlyTo(value: String)

  infix fun String.onlyTo(values: List<String>)
}

private class HeadersBuilderSpec(val builder: HeadersBuilder = HeadersBuilder()) : HeadersSpec {
  override fun String.to(value: String) {
    builder.add(this, value)
  }

  override fun String.to(values: List<String>) {
    values.forEach { value -> builder.add(this, value) }
  }

  override fun String.onlyTo(value: String) {
    builder.set(this, value)
  }

  override fun String.onlyTo(values: List<String>) {
    TODO()
  }
}

private class RequestHeadersSpec(private val request: MutableRequest) : HeadersSpec {
  override fun String.to(value: String) {
    request.header(this, value)
  }

  override infix fun String.to(values: List<String>) {
    values.forEach { value ->
      request.header(this@to, value)
    }
  }

  override infix fun String.onlyTo(value: String) {
    request.setHeader(this, value)
  }

  override infix fun String.onlyTo(values: List<String>) {
    TODO()
  }
}

private class ClientDefaultHeadersSpec(private val builder: Methanol.Builder) : HeadersSpec {
  override fun String.to(value: String) {
    builder.defaultHeader(this, value)
  }

  override fun String.to(values: List<String>) {
    TODO("Not yet implemented")
  }

  override fun String.onlyTo(value: String) {
    TODO("Not yet implemented")
  }

  override fun String.onlyTo(values: List<String>) {
    TODO("Not yet implemented")
  }
}

class ClientSpec(val builder: Methanol.Builder = Methanol.newBuilder()) {
  fun userAgent(userAgent: String) {
    builder.userAgent(userAgent)
  }

  fun baseUri(uri: String) {
    builder.baseUri(uri)
  }

  fun baseUri(uri: URI) {
    builder.baseUri(uri)
  }

  fun defaultHeaders(block: HeadersSpec.() -> Unit) = ClientDefaultHeadersSpec(builder).block()

  fun requestTimeout(timeout: Duration) {
    builder.requestTimeout(timeout.toJavaDuration())
  }

  fun headersTimeout(timeout: Duration) {
    builder.headersTimeout(timeout.toJavaDuration())
  }

  fun readTimeout(timeout: Duration) {
    builder.headersTimeout(timeout.toJavaDuration())
  }

  fun adapterCodec(block: AdapterCodecSpec.() -> Unit) {
    builder.adapterCodec(AdapterCodecSpec().apply(block).builder.build())
  }

  fun adapterCodec(adapterCodec: AdapterCodec) {
    builder.adapterCodec(adapterCodec)
  }

  fun autoAcceptEncoding(autoAcceptEncoding: Boolean) {
    builder.autoAcceptEncoding(autoAcceptEncoding)
  }

  fun interceptors(block: InterceptorsSpec.() -> Unit) {
    InterceptorsSpec(builder).apply(block)
  }

  fun cache(block: CacheSpec.() -> Unit) {
    builder.cache(CacheSpec().apply(block).builder.build())
  }

  fun cacheChain(block: CacheChainSpec.() -> Unit) {
    val caches = CacheChainSpec().apply(block).cacheSpecs.map { it.builder.build() }.toTypedArray()
    require(caches.isNotEmpty()) {
      "Must have at least one cache in the chaain"
    }
    builder.cache(caches.first(), *caches.sliceArray(1..<caches.size))
  }

  fun cookieHandler(cookieHandler: CookieHandler) {
    builder.cookieHandler(cookieHandler)
  }

  fun connectTimeout(timeout: Duration) {
    builder.connectTimeout(timeout.toJavaDuration())
  }

  fun sslContext(sslContext: SSLContext) {
    builder.sslContext(sslContext)
  }

  fun sslParameters(sslParameters: SSLParameters) {
    builder.sslParameters(sslParameters)
  }

  fun executor(executor: Executor) {
    builder.executor(executor)
  }

  fun followRedirects(redirect: Redirect) {
    builder.followRedirects(redirect)
  }

  fun version(version: Version) {
    builder.version(version)
  }

  fun priority(priority: Int) {
    builder.priority(priority)
  }

  fun proxy(proxySelector: ProxySelector) {
    builder.proxy(proxySelector)
  }

  fun authenticator(authenticator: Authenticator) {
    builder.authenticator(authenticator)
  }
}

class RequestSpec(val request: MutableRequest = MutableRequest.create()) {
  private val headersSpec: HeadersSpec = RequestHeadersSpec(request)

  fun uri(uri: String) {
    request.uri(uri)
  }

  fun uri(uri: URI) {
    request.uri(uri)
  }

  fun headers(block: HeadersSpec.() -> Unit) {
    headersSpec.block()
  }

  fun cacheControl(block: CacheControlSpec.() -> Unit) =
    cacheControl(CacheControlSpec().apply(block).builder.build())

  fun cacheControl(cacheControl: CacheControl) {
    request.cacheControl(cacheControl)
  }

  fun adapterCodec(block: AdapterCodecSpec.() -> Unit) =
    adapterCodec(AdapterCodecSpec().apply(block).builder.build())

  fun adapterCodec(adapterCodec: AdapterCodec) {
    request.adapterCodec(adapterCodec)
  }

  fun expectContinue(expectContinue: Boolean) {
    request.expectContinue(expectContinue)
  }

  fun version(version: Version) {
    request.version(version)
  }

  fun timeout(timeout: Duration) {
    request.timeout(timeout.toJavaDuration())
  }

  fun tags(block: TagsSpec.() -> Unit) {
    TagsSpec(request).apply(block)
  }

  fun GET() {
    request.GET()
  }

  fun POST(block: PostBodySpec.() -> Unit) {
    PostBodySpec(request).apply(block)
  }

  fun POST(bodyPublisher: BodyPublisher) {
    request.POST(bodyPublisher)
  }

  fun POST(payload: Any, mediaType: MediaType) {
    request.POST(payload, mediaType)
  }

  fun PUT(bodyPublisher: BodyPublisher) {
    request.PUT(bodyPublisher)
  }

  fun PUT(payload: Any, mediaType: MediaType) {
    request.PUT(payload, mediaType)
  }

  fun PATCH(bodyPublisher: BodyPublisher) {
    request.PATCH(bodyPublisher)
  }

  fun PATCH(payload: Any, mediaType: MediaType) {
    request.PATCH(payload, mediaType)
  }

  fun DELETE() {
    request.DELETE()
  }

  fun method(method: String, bodyPublisher: BodyPublisher) {
    request.method(method, bodyPublisher)
  }

  fun method(method: String, payload: Any, mediaType: MediaType) {
    request.method(method, payload, mediaType)
  }
}

class PostBodySpec(private val request: MutableRequest) {
  fun multipart(block: MultipartSpec.() -> Unit) {
    request.POST(MultipartSpec().apply(block).builder.build())
  }

  fun form(block: FormSpec.() -> Unit) {
    request.POST(FormSpec().apply(block).builder.build())
  }

  fun payload(any: Any, mediaType: MediaType) {
    request.POST(any, mediaType)
  }
}

class FormSpec(val builder: FormBodyPublisher.Builder = FormBodyPublisher.newBuilder()) {
  infix fun String.to(value: String) {
    builder.query(this, value)
  }
}

class MultipartSpec(val builder: MultipartBodyPublisher.Builder = MultipartBodyPublisher.newBuilder()) {
  infix fun String.to(value: String) {
    builder.textPart(this, value)
  }

  fun String.to(value: String, charset: Charset) {
    builder.textPart(this, value, charset)
  }

  infix fun String.to(path: Path) {
    builder.filePart(this, path)
  }

  fun String.to(path: Path, mediaType: MediaType) {
    builder.filePart(this, path, mediaType)
  }

  infix fun String.to(bodyPublisher: BodyPublisher) {
    builder.formPart(this, bodyPublisher)
  }

  fun String.to(bodyPublisher: BodyPublisher, filename: String) {
    builder.formPart(this, filename, bodyPublisher)
  }

  fun mediaType(mediaType: MediaType) {
    builder.mediaType(mediaType)
  }

  fun boundary(boundary: String) {
    builder.boundary(boundary)
  }

  fun part(bodyPublisher: BodyPublisher, block: HeadersSpec.() -> Unit) {
    builder.part(
      MultipartBodyPublisher.Part.create(
        HeadersBuilderSpec().apply(block).builder.build(),
        bodyPublisher
      )
    )
  }

  fun part(part: MultipartBodyPublisher.Part) {
    builder.part(part)
  }
}

class CacheControlSpec(val builder: CacheControl.Builder = CacheControl.newBuilder()) {
  fun maxAge(maxAge: Duration) {
    builder.maxAge(maxAge.toJavaDuration())
  }

  fun minFresh(minFresh: Duration) {
    builder.minFresh(minFresh.toJavaDuration())
  }

  fun maxStale(maxStale: Duration) {
    builder.maxStale(maxStale.toJavaDuration())
  }

  fun anyMaxStale() {
    builder.anyMaxStale()
  }

  fun staleIfError(staleIfError: Duration) {
    builder.staleIfError(staleIfError.toJavaDuration())
  }

  fun noCache() {
    builder.noCache()
  }

  fun noStore() {
    builder.noStore()
  }

  fun noTransform() {
    builder.noTransform()
  }

  fun onlyIfCached() {
    builder.onlyIfCached()
  }

  operator fun String.unaryPlus() {
    builder.directive(this)
  }

  infix fun String.to(value: String) {
    builder.directive(this, value)
  }
}

class TagsSpec(val request: MutableRequest) {
  operator fun Any.unaryPlus() {
    request.tag(this)
  }

  inline fun <reified T> add(value: T) {
    request.tag(object : TypeRef<T>() {}, value)
  }
}

class AdapterCodecSpec(val builder: AdapterCodec.Builder = AdapterCodec.newBuilder()) {
  operator fun Encoder.unaryPlus() {
    builder.encoder(this);
  }

  operator fun Decoder.unaryPlus() {
    builder.decoder(this)
  }
}

class CacheSpec(val builder: HttpCache.Builder = HttpCache.newBuilder()) {
  fun onDisk(path: Path, maxSizeBytes: Long) {
    builder.cacheOnDisk(path, maxSizeBytes)
  }

  fun inMemory(maxSizeBytes: Long) {
    builder.cacheOnMemory(maxSizeBytes)
  }

  fun on(storageExtension: StorageExtension) {
    builder.cacheOn(storageExtension)
  }

  fun executor(executor: Executor) {
    builder.executor(executor)
  }
}

class CacheChainSpec(internal val cacheSpecs: MutableList<CacheSpec> = ArrayList()) {
  fun cache(block: CacheSpec.() -> Unit) {
    cacheSpecs += CacheSpec().apply(block)
  }
}

interface BaseInterceptorsSpec {
  operator fun CoroutineInterceptor.unaryPlus()
}

class InterceptorsSpec(private val builder: Methanol.Builder) : BaseInterceptorsSpec {
  override operator fun CoroutineInterceptor.unaryPlus() {
    builder.interceptor(InterceptorAdapter(this));
  }

  fun backend(block: BaseInterceptorsSpec.() -> Unit) {
    object : BaseInterceptorsSpec {
      override fun CoroutineInterceptor.unaryPlus() {
        builder.backendInterceptor(InterceptorAdapter(this));
      }
    }.apply(block)
  }
}

fun <T> Interceptor.Chain<T>.toCoroutineChain(): CoroutineInterceptor.Chain<T> =
  CoroutineChainAdapter(this)

interface CoroutineInterceptor {
  suspend fun <T> intercept(request: HttpRequest, chain: Chain<T>): HttpResponse<T>

  interface Chain<T> {
    suspend fun forward(request: HttpRequest): HttpResponse<T>

    val innerChain: Interceptor.Chain<T>
  }
}

private class CoroutineChainAdapter<T>(override val innerChain: Interceptor.Chain<T>) :
  CoroutineInterceptor.Chain<T> {
  override suspend fun forward(request: HttpRequest): HttpResponse<T> =
    innerChain.forwardAsync(request).await()
}

private class InterceptorAdapter(private val innerCoroutineInterceptor: CoroutineInterceptor) :
  Interceptor {
  override fun <T : Any?> intercept(
    request: HttpRequest,
    chain: Interceptor.Chain<T>
  ): HttpResponse<T> =
    runBlocking {
      innerCoroutineInterceptor.intercept(request, chain.toCoroutineChain())
    }

  override fun <T : Any?> interceptAsync(
    request: HttpRequest,
    chain: Interceptor.Chain<T>
  ): CompletableFuture<HttpResponse<T>> = request.tag<CoroutineScopeHolder>()?.scope?.future {
    innerCoroutineInterceptor.intercept(request, chain.toCoroutineChain())
  } ?: throw IllegalStateException("Request has no CoroutineScope")
}

suspend inline fun <reified T> Methanol.request(
  uri: String,
  noinline block: RequestSpec.() -> Unit
) = request(uri, object : TypeRef<T>() {}, block)

suspend fun <T> Methanol.request(
  uri: String,
  type: TypeRef<T>,
  block: RequestSpec.() -> Unit
): HttpResponse<T> =
  RequestSpec(MutableRequest.create(uri)).apply(block).run {
    coroutineScope {
      tags {
        +CoroutineScopeHolder(this@coroutineScope)
      }
      sendAsync(request.toImmutableRequest(), type).await()
    }
  }

suspend fun <T> Methanol.request(
  uri: String,
  bodyHandler: BodyHandler<T>,
  block: RequestSpec.() -> Unit
): HttpResponse<T> =
  RequestSpec(MutableRequest.create(uri)).apply(block).run {
    coroutineScope {
      tags {
        +CoroutineScopeHolder(this@coroutineScope)
      }
      sendAsync(request.toImmutableRequest(), bodyHandler).await()
    }
  }

fun client(block: ClientSpec.() -> Unit): Methanol = ClientSpec().apply(block).builder.build()

private class CoroutineScopeHolder(val scope: CoroutineScope)

fun HttpRequest.toTaggableRequest(): TaggableRequest = TaggableRequest.from(this)

inline fun <reified T : Any> HttpRequest.tag(): T? =
  toTaggableRequest().tag(object : TypeRef<T>() {}).orElse(null)

fun String.toMediaType(): MediaType = MediaType.parse(this)

fun String.toCacheControl(): CacheControl = CacheControl.parse(this)

fun List<String>.toCacheControl(): CacheControl = CacheControl.parse(this)

fun BodyPublisher.withMediaType(mediaType: MediaType): MimeBodyPublisher =
  MoreBodyPublishers.ofMediaType(this, mediaType)

fun main() {
  val client = client {
    cacheChain {
      cache {
        inMemory(1000)
      }

      cache {
        on(RedisStorageExtension.newBuilder().standalone(RedisURI.create("")).build())
      }
    }
  }

  runBlocking {
    client.request<String>("") {
      headers {
        "Accept" to "application/json"
      }
    }
  }
}
