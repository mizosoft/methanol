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

import com.github.mizosoft.methanol.*
import com.github.mizosoft.methanol.BodyAdapter.Hints
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder
import java.net.URI
import java.net.http.HttpRequest
import java.nio.charset.Charset
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** A [spec][Spec] for configuring a [Request][com.github.mizosoft.methanol.kotlin.Request], excluding the request method and body. */
@Spec
interface BaseRequestSpec {
  fun uri(uri: String)

  fun uri(uri: URI)

  fun headers(block: HeadersSpec.() -> Unit)

  fun cacheControl(block: CacheControlSpec.() -> Unit)

  fun cacheControl(cacheControl: CacheControl)

  fun adapterCodec(block: AdapterCodecSpec.() -> Unit)

  fun adapterCodec(adapterCodec: AdapterCodec)

  fun expectContinue(expectContinue: Boolean)

  fun version(version: HttpVersion)

  fun timeout(timeout: Duration)

  fun tags(block: TagsSpec.() -> Unit)

  fun hints(block: HintsSpec.() -> Unit)
}

/** A [spec][Spec] for configuring a [Request][com.github.mizosoft.methanol.kotlin.Request]'s method and body. */
@Spec
@Suppress("FunctionName")
interface RequestMethodSpec {
  fun GET()

  fun DELETE()

  fun POST(block: RequestBodySpec.() -> Unit)

  fun PUT(block: RequestBodySpec.() -> Unit)

  fun PATCH(block: RequestBodySpec.() -> Unit)

  fun method(method: String, block: RequestBodySpec.() -> Unit)
}

/** A [spec][Spec] for configuring a [Request][com.github.mizosoft.methanol.kotlin.Request]'s body assuming its method has been set. */
@Spec
interface RequestBodySpec {
  fun body(bodyPublisher: BodyPublisher)

  fun body(payload: Any, mediaType: MediaType)

  fun multipartBody(block: MultipartBodySpec.() -> Unit)

  fun formBody(block: FormBodySpec.() -> Unit)
}

@PublishedApi
internal interface MutableRequestBodySpec {
  val request: MutableRequest
}

/** A [spec][Spec] for configuring a [Request][com.github.mizosoft.methanol.kotlin.Request]. */
@Spec
interface RequestSpec : BaseRequestSpec, RequestMethodSpec

/** A [spec][Spec] for configuring a [Request][com.github.mizosoft.methanol.kotlin.Request] whose method has been set */
@Spec
interface RequestWithKnownMethodSpec : BaseRequestSpec, RequestBodySpec

internal open class BaseRequestFactorySpec(val request: MutableRequest = MutableRequest.create()) :
  BaseRequestSpec, FactorySpec<TaggableRequest> {
  override fun uri(uri: String) {
    request.uri(uri)
  }

  override fun uri(uri: URI) {
    request.uri(uri)
  }

  override fun headers(block: HeadersSpec.() -> Unit) {
    HeadersAccumulatorSpec(request).block()
  }

  override fun cacheControl(block: CacheControlSpec.() -> Unit) {
    request.cacheControl(CacheControl(block))
  }

  override fun cacheControl(cacheControl: CacheControl) {
    request.cacheControl(cacheControl)
  }

  override fun adapterCodec(block: AdapterCodecSpec.() -> Unit) {
    request.adapterCodec(AdapterCodec(block))
  }

  override fun adapterCodec(adapterCodec: AdapterCodec) {
    request.adapterCodec(adapterCodec)
  }

  override fun expectContinue(expectContinue: Boolean) {
    request.expectContinue(expectContinue)
  }

  override fun version(version: HttpVersion) {
    request.version(version)
  }

  override fun timeout(timeout: Duration) {
    request.timeout(timeout.toJavaDuration())
  }

  override fun tags(block: TagsSpec.() -> Unit) {
    TagsSpec(request).apply(block)
  }

  override fun hints(block: HintsSpec.() -> Unit) {
    request.hints { builder -> HintsSpec(builder).block() }
  }

  override fun make(): TaggableRequest = request.toImmutableRequest()
}

internal class RequestFactorySpec(request: MutableRequest = MutableRequest.create()) :
  BaseRequestFactorySpec(request), RequestSpec {
  override fun GET() {
    request.GET()
  }

  override fun DELETE() {
    request.DELETE()
  }

  override fun POST(block: RequestBodySpec.() -> Unit) {
    method("POST", block)
  }

  override fun PUT(block: RequestBodySpec.() -> Unit) {
    method("PUT", block)
  }

  override fun PATCH(block: RequestBodySpec.() -> Unit) {
    method("PATCH", block)
  }

  override fun method(method: String, block: RequestBodySpec.() -> Unit) {
    var called = false
    object : RequestBodySpec, MutableRequestBodySpec {
      override val request = this@RequestFactorySpec.request // Declare explicit receiver.

      override fun body(bodyPublisher: BodyPublisher) {
        called = true
        request.method(method, bodyPublisher)
      }

      override fun body(payload: Any, mediaType: MediaType) {
        called = true
        request.method(method, payload, mediaType)
      }

      override fun multipartBody(block: MultipartBodySpec.() -> Unit) {
        called = true
        request.method(method, MultipartBody(block))
      }

      override fun formBody(block: FormBodySpec.() -> Unit) {
        called = true
        request.method(method, FormBody(block))
      }
    }.block()

    // If the block doesn't set a body set an empty one.
    if (!called) {
      request.method(method, BodyPublishers.noBody())
    }
  }
}

internal class RequestWithKnownMethodFactorySpec(private val method: String) :
  BaseRequestFactorySpec(), RequestWithKnownMethodSpec, MutableRequestBodySpec {
  init {
    if (method.equals("GET", ignoreCase = true)) {
      request.GET()
    } else if (method.equals("DELETE", ignoreCase = true)) {
      request.DELETE()
    } else {
      request.method(method, BodyPublishers.noBody())
    }
  }

  override fun body(bodyPublisher: BodyPublisher) {
    request.method(method, bodyPublisher)
  }

  override fun body(payload: Any, mediaType: MediaType) {
    request.method(method, payload, mediaType)
  }

  override fun multipartBody(block: MultipartBodySpec.() -> Unit) {
    request.method(method, MultipartBody(block))
  }

  override fun formBody(block: FormBodySpec.() -> Unit) {
    request.method(method, FormBody(block))
  }
}

/** A [spec][Spec] for configuring a [FormBody][com.github.mizosoft.methanol.kotlin.FormBody]. */
@Spec
interface FormBodySpec : StringNameMultiStringValueSpec

internal class FormBodyFactorySpec(
  private val builder: FormBodyPublisher.Builder = FormBody.newBuilder()
) : FormBodySpec, FactorySpec<FormBody> {
  override infix fun String.to(value: String) {
    builder.query(this, value)
  }

  override infix fun String.to(values: List<String>) {
    values.forEach { value -> builder.query(this, value) }
  }

  override fun make(): FormBody = builder.build()
}

/** A [spec][Spec] for configuring a [MultipartBody][com.github.mizosoft.methanol.kotlin.MultipartBody]. */
@Spec
interface MultipartBodySpec : PairCreationDisablingSpec {
  infix fun String.to(value: String)

  fun String.to(value: String, charset: Charset)

  infix fun String.to(file: Path)

  fun String.to(file: Path, mediaType: MediaType)

  infix fun String.to(bodyPublisher: BodyPublisher)

  fun String.to(bodyPublisher: BodyPublisher, filename: String)

  fun String.to(bodyPublisher: BodyPublisher, filename: String, mediaType: MediaType)

  operator fun MultipartBodyPart.unaryPlus()

  fun mediaType(mediaType: MediaType)

  fun boundary(boundary: String)
}

internal class MultipartBodyFactorySpec(
  private val builder: MultipartBodyPublisher.Builder = MultipartBody.newBuilder()
) : MultipartBodySpec, FactorySpec<MultipartBody> {
  override infix fun String.to(value: String) {
    builder.textPart(this, value)
  }

  override fun String.to(value: String, charset: Charset) {
    builder.textPart(this, value, charset)
  }

  override infix fun String.to(file: Path) {
    builder.filePart(this, file)
  }

  override fun String.to(file: Path, mediaType: MediaType) {
    builder.filePart(this, file, mediaType)
  }

  override infix fun String.to(bodyPublisher: BodyPublisher) {
    builder.formPart(this, bodyPublisher)
  }

  override fun String.to(bodyPublisher: BodyPublisher, filename: String) {
    builder.formPart(this, filename, bodyPublisher)
  }

  override fun String.to(bodyPublisher: BodyPublisher, filename: String, mediaType: MediaType) {
    builder.formPart(this, filename, bodyPublisher, mediaType)
  }

  override fun MultipartBodyPart.unaryPlus() {
    builder.part(this)
  }

  override fun mediaType(mediaType: MediaType) {
    builder.mediaType(mediaType)
  }

  override fun boundary(boundary: String) {
    builder.boundary(boundary)
  }

  override fun make(): MultipartBody = builder.build()
}

/** A [spec][Spec] for configuring [request tags][TaggableRequest]. */
@Spec
class TagsSpec(@PublishedApi internal val builder: TaggableRequest.Builder) {
  inline operator fun <reified T> T.unaryPlus() {
    builder.tag(object : TypeRef<T>() {}, this)
  }
}

/** A [spec][Spec] for configuring the [hints][Hints] passed to [adapters][AdapterCodec]. */
@Spec
class HintsSpec(@PublishedApi internal val builder: Hints.Builder) {
  inline operator fun <reified T> T.unaryPlus() {
    builder.put(T::class.java, this)
  }
}

/**
 * Returns the tag associated with the given type if this request is a [TaggableRequest] and it has
 * such a tag, or {@code null} otherwise.
 */
inline fun <reified T : Any> Request.tag(): T? =
  TaggableRequest.tagOf(this, object : TypeRef<T>() {}).orElse(null)

/** Returns this request if it is a [TaggableRequest], or a [TaggableRequest] copy with no tags otherwise. */
fun Request.toTaggableRequest(): TaggableRequest = TaggableRequest.from(this)

inline fun <reified T> RequestBodySpec.body(payload: T, mediaType: MediaType) {
  require(this is MutableRequestBodySpec) { "Unknown implementation of RequestBodySpec: ${this::class}" }
  request.method(request.method(), payload, TypeRef<T>(), mediaType)
}

/**
 * Associated the given media type with this [BodyPublisher] through a [MimeBodyPublisher]. This
 * allows the [Client] to automatically add a corresponding `Content-Type` header.
 */
fun BodyPublisher.withMediaType(mediaType: MediaType): MimeBodyPublisher =
  MoreBodyPublishers.ofMediaType(this, mediaType)

typealias Request = HttpRequest

typealias BodyPublisher = HttpRequest.BodyPublisher

typealias BodyPublishers = HttpRequest.BodyPublishers

typealias MultipartBody = MultipartBodyPublisher

typealias MultipartBodyPart = MultipartBodyPublisher.Part

typealias FormBody = FormBodyPublisher

/** Creates a new [Request][com.github.mizosoft.methanol.kotlin.Request] as configured by the given spec block. */
@Suppress("FunctionName")
fun Request(block: RequestSpec.() -> Unit) = RequestFactorySpec().apply(block).make()

/** Creates a copy of the given request after configuring with the given spec block. */
@Suppress("FunctionName")
fun Request(request: Request, block: RequestSpec.() -> Unit) =
  RequestFactorySpec(MutableRequest.copyOf(request)).apply(block).make()

/** Creates a new [MultipartBody][com.github.mizosoft.methanol.kotlin.MultipartBody] as configured by the given spec block. */
@Suppress("FunctionName")
fun MultipartBody(block: MultipartBodySpec.() -> Unit) =
  MultipartBodyFactorySpec().apply(block).make()

/** Creates a new [FormBody][com.github.mizosoft.methanol.kotlin.FormBody] as configured by the given spec block. */
@Suppress("FunctionName")
fun FormBody(block: FormBodySpec.() -> Unit) = FormBodyFactorySpec().apply(block).make()

/** Creates a new [MultipartBodyPart][com.github.mizosoft.methanol.kotlin.MultipartBodyPart] as configured by the given spec block. */
@Suppress("FunctionName")
fun MultipartBodyPart(
  bodyPublisher: BodyPublisher,
  block: HeadersSpec.() -> Unit
): MultipartBodyPart = HeadersBuilder().let { headersBuilder ->
  HeadersAccumulatorSpec(headersBuilder.asHeadersAccumulator()).block()
  MultipartBodyPart.create(headersBuilder.build(), bodyPublisher)
}
