/*
 * Copyright (c) 2025 Moataz Hussein
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

import com.github.mizosoft.methanol.AdapterCodec
import com.github.mizosoft.methanol.Methanol
import com.github.mizosoft.methanol.TaggableRequest
import com.github.mizosoft.methanol.TypeRef
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.future.await
import java.net.Authenticator
import java.net.CookieHandler
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.util.concurrent.Executor
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * A [spec][Spec] for configuring a [Client][com.github.mizosoft.methanol.kotlin.Client].
 *
 * See [Methanol.Builder].
 */
@Spec
interface ClientSpec {
  fun userAgent(userAgent: String)

  fun baseUri(uri: String)

  fun baseUri(uri: URI)

  fun defaultHeaders(block: HeadersSpec.() -> Unit)

  fun requestTimeout(timeout: Duration)

  fun headersTimeout(timeout: Duration)

  fun readTimeout(timeout: Duration)

  fun adapterCodec(block: AdapterCodecSpec.() -> Unit)

  fun adapterCodec(adapterCodec: AdapterCodec)

  fun autoAcceptEncoding(autoAcceptEncoding: Boolean)

  fun interceptors(block: InterceptorsSpec.() -> Unit)

  fun backendInterceptors(block: InterceptorsSpec.() -> Unit)

  fun cache(block: CacheSpec.() -> Unit)

  fun cache(cache: Cache)

  fun cacheChain(block: CacheChainSpec.() -> Unit)

  fun cacheChain(cacheChain: CacheChain)

  fun cookieHandler(cookieHandler: CookieHandler)

  fun connectTimeout(timeout: Duration)

  fun sslContext(sslContext: SSLContext)

  fun sslParameters(sslParameters: SSLParameters)

  fun executor(executor: Executor)

  fun followRedirects(redirect: Redirect)

  fun version(version: HttpVersion)

  fun priority(priority: Int)

  fun proxy(proxySelector: ProxySelector)

  fun authenticator(authenticator: Authenticator)
}

private class ClientFactorySpec(private val builder: Methanol.Builder = Methanol.newBuilder()) :
  ClientSpec, FactorySpec<Client> {
  override fun userAgent(userAgent: String) {
    builder.userAgent(userAgent)
  }

  override fun baseUri(uri: String) {
    builder.baseUri(uri)
  }

  override fun baseUri(uri: URI) {
    builder.baseUri(uri)
  }

  override fun defaultHeaders(block: HeadersSpec.() -> Unit) {
    builder.defaultHeaders { HeadersAccumulatorSpec(it).block() }
  }

  override fun requestTimeout(timeout: Duration) {
    builder.requestTimeout(timeout.toJavaDuration())
  }

  override fun headersTimeout(timeout: Duration) {
    builder.headersTimeout(timeout.toJavaDuration())
  }

  override fun readTimeout(timeout: Duration) {
    builder.readTimeout(timeout.toJavaDuration())
  }

  override fun adapterCodec(block: AdapterCodecSpec.() -> Unit) {
    builder.adapterCodec(AdapterCodec(block))
  }

  override fun adapterCodec(adapterCodec: AdapterCodec) {
    builder.adapterCodec(adapterCodec)
  }

  override fun autoAcceptEncoding(autoAcceptEncoding: Boolean) {
    builder.autoAcceptEncoding(autoAcceptEncoding)
  }

  /** @see [Methanol.Builder.interceptor] */
  override fun interceptors(block: InterceptorsSpec.() -> Unit) {
    object : InterceptorsSpec {
      override fun Interceptor.unaryPlus() {
        this@ClientFactorySpec.builder.interceptor(this.toMethanolInterceptor())
      }
    }.block()
  }

  /** @see [Methanol.Builder.backendInterceptor] */
  override fun backendInterceptors(block: InterceptorsSpec.() -> Unit) {
    object : InterceptorsSpec {
      override fun Interceptor.unaryPlus() {
        this@ClientFactorySpec.builder.backendInterceptor(this.toMethanolInterceptor())
      }
    }.block()
  }

  override fun cache(block: CacheSpec.() -> Unit) {
    builder.cache(Cache(block))
  }

  override fun cache(cache: Cache) {
    builder.cache(cache)
  }

  override fun cacheChain(block: CacheChainSpec.() -> Unit) {
    builder.cacheChain(CacheChain(block))
  }

  override fun cacheChain(cacheChain: CacheChain) {
    builder.cacheChain(cacheChain)
  }

  override fun cookieHandler(cookieHandler: CookieHandler) {
    builder.cookieHandler(cookieHandler)
  }

  override fun connectTimeout(timeout: Duration) {
    builder.connectTimeout(timeout.toJavaDuration())
  }

  override fun sslContext(sslContext: SSLContext) {
    builder.sslContext(sslContext)
  }

  override fun sslParameters(sslParameters: SSLParameters) {
    builder.sslParameters(sslParameters)
  }

  override fun executor(executor: Executor) {
    builder.executor(executor)
  }

  override fun followRedirects(redirect: Redirect) {
    builder.followRedirects(redirect)
  }

  override fun version(version: HttpVersion) {
    builder.version(version)
  }

  override fun priority(priority: Int) {
    builder.priority(priority)
  }

  override fun proxy(proxySelector: ProxySelector) {
    builder.proxy(proxySelector)
  }

  override fun authenticator(authenticator: Authenticator) {
    builder.authenticator(authenticator)
  }

  override fun make(): Client = builder.build()
}

/** A [spec][Spec] for adding [interceptors][Interceptor] to a client. */
@Spec
interface InterceptorsSpec {
  operator fun Interceptor.unaryPlus()
}

typealias Client = Methanol

typealias HttpVersion = HttpClient.Version

typealias Redirect = HttpClient.Redirect

/** Creates a new [Client][com.github.mizosoft.methanol.kotlin.Client] as configured by the given spec block. */
@Suppress("FunctionName")
fun Client(block: ClientSpec.() -> Unit) = ClientFactorySpec().apply(block).make()

private inline fun <R> Client.createRequestWithCoroutineScope(
  uri: String,
  coroutineContext: CoroutineContext,
  spec: R,
  block: R.() -> Unit
) where R : BaseRequestSpec, R : FactorySpec<out TaggableRequest> = spec.run {
  uri(uri)
  block()
  if (interceptors().isNotEmpty() || backendInterceptors().isNotEmpty()) {
    attachCoroutineScope(coroutineContext)
  }
  make()
}

@PublishedApi
internal fun BaseRequestSpec.attachCoroutineScope(coroutineContext: CoroutineContext) {
  tags {
    +CoroutineScopeHolder(CoroutineScope(coroutineContext))
  }
}

@PublishedApi
internal suspend fun <T> Client.fetch(
  uri: String,
  typeRef: TypeRef<T>,
  block: RequestSpec.() -> Unit = {}
): Response<T> = sendAsync(
  createRequestWithCoroutineScope(
    uri,
    coroutineContext,
    RequestFactorySpec(),
    block
  ), typeRef
).await()

@PublishedApi
internal suspend fun <T> Client.fetch(
  method: String,
  uri: String,
  typeRef: TypeRef<T>,
  block: RequestWithKnownMethodSpec.() -> Unit = {}
): Response<T> = sendAsync(
  createRequestWithCoroutineScope(
    uri,
    coroutineContext,
    RequestWithKnownMethodFactorySpec(method),
    block
  ),
  typeRef
).await()

@PublishedApi
internal suspend fun <T> Client.fetch(
  method: String,
  uri: String,
  bodyHandler: BodyHandler<T>,
  block: RequestWithKnownMethodSpec.() -> Unit = {}
): Response<T> = sendAsync(
  createRequestWithCoroutineScope(
    uri,
    coroutineContext,
    RequestWithKnownMethodFactorySpec(method),
    block
  ),
  bodyHandler
).await()

/** Fetches the response to sending the given request, mapping the response body into [T]. */
suspend inline fun <reified T> Client.fetch(request: Request): Response<T> {
  var finalRequest = request
  if (interceptors().isNotEmpty() || backendInterceptors().isNotEmpty()) {
    val localCoroutineContext = coroutineContext
    finalRequest = Request(request) {
      attachCoroutineScope(localCoroutineContext)
    }
  }
  return send(finalRequest, TypeRef<T>())
}

/**
 * Fetches the response to sending a request to the given URI, mapping the response body into [T].
 * The request has a GET method by default. A spec block can be optionally passed to
 * customize the request.
 */
suspend inline fun <reified T> Client.fetch(
  uri: String,
  noinline block: RequestSpec.() -> Unit = {}
) = fetch(uri, TypeRef<T>(), block)

/**
 * Fetches the response sending a request to the given URI, mapping the response body with the given
 * [BodyHandler]. The request has a GET method by default. A spec block can be optionally
 * passed to customize the request.
 */
suspend fun <T> Client.fetch(
  uri: String,
  bodyHandler: BodyHandler<T>,
  block: RequestSpec.() -> Unit = {}
): Response<T> = sendAsync(
  createRequestWithCoroutineScope(
    uri,
    coroutineContext,
    RequestFactorySpec(),
    block
  ), bodyHandler
).await()

/**
 * Fetches the response to sending a GET request to the given URI, mapping the response body into
 * [T]. A spec block can be optionally passed to customize the request.
 */
suspend inline fun <reified T> Client.get(
  uri: String,
  noinline block: BaseRequestSpec.() -> Unit = {}
) = fetch("GET", uri, TypeRef<T>(), block)

/**
 * Fetches the response to sending a GET request to the given URI, mapping the response body with
 * the given [BodyHandler]. A spec block can be optionally passed to customize the request.
 */
suspend fun <T> Client.get(
  uri: String,
  bodyHandler: BodyHandler<T>,
  block: BaseRequestSpec.() -> Unit = {}
) = fetch<T>("GET", uri, bodyHandler, block)

/**
 * Fetches the response to sending a DELETE request to the given URI, mapping the response body into
 * [T]. A spec block can be optionally passed to customize the request.
 */
suspend inline fun <reified T> Client.delete(
  uri: String,
  noinline block: BaseRequestSpec.() -> Unit = {}
) = fetch("DELETE", uri, TypeRef<T>(), block)

/**
 * Fetches the response to sending a DELETE request to the given URI, mapping the response body with
 * the given [BodyHandler]. A spec block can be optionally passed to customize the request.
 */
suspend fun <T> Client.delete(
  uri: String,
  bodyHandler: BodyHandler<T>,
  block: BaseRequestSpec.() -> Unit = {}
) = fetch("DELETE", uri, bodyHandler, block)

/**
 * Fetches the response to sending a POST request to the given URI, mapping the response body into
 * [T]. A spec block can be optionally passed to customize the request.
 */
suspend inline fun <reified T> Client.post(
  uri: String,
  noinline block: RequestWithKnownMethodSpec.() -> Unit = {}
) = fetch("POST", uri, TypeRef<T>(), block)

/**
 * Fetches the response to sending a POST request to the given URI, mapping the response body with
 * the given [BodyHandler]. A spec block can be optionally passed to customize the request.
 */
suspend fun <T> Client.post(
  uri: String,
  bodyHandler: BodyHandler<T>,
  block: RequestWithKnownMethodSpec.() -> Unit = {}
) = fetch("POST", uri, bodyHandler, block)

/**
 * Fetches the response to sending a PUT request to the given URI, mapping the response body into
 * [T]. A spec block can be optionally passed to customize the request.
 */
suspend inline fun <reified T> Client.put(
  uri: String,
  noinline block: RequestWithKnownMethodSpec.() -> Unit = {}
) = fetch("PUT", uri, TypeRef<T>(), block)

/**
 * Fetches the response to sending a PUT request to the given URI, mapping the response body with
 * the given [BodyHandler]. A spec block can be optionally passed to customize the request.
 */
suspend fun <T> Client.put(
  uri: String,
  bodyHandler: BodyHandler<T>,
  block: RequestWithKnownMethodSpec.() -> Unit = {}
) = fetch("PUT", uri, bodyHandler, block)

/**
 * Fetches the response to sending a PATCH request to the given URI, mapping the response body into
 * [T]. A spec block can be optionally passed to customize the request.
 */
suspend inline fun <reified T> Client.patch(
  uri: String,
  noinline block: RequestWithKnownMethodSpec.() -> Unit = {}
) = fetch("PATCH", uri, TypeRef<T>(), block)

/**
 * Fetches the response to sending a PATCH request to the given URI, mapping the response body with
 * the given [BodyHandler]. A spec block can be optionally passed to customize the request.
 */
suspend fun <T> Client.patch(
  uri: String,
  bodyHandler: BodyHandler<T>,
  block: RequestWithKnownMethodSpec.() -> Unit = {}
) = fetch("PATCH", uri, bodyHandler, block)
