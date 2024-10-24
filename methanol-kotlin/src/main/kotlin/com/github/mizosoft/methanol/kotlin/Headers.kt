package com.github.mizosoft.methanol.kotlin

import com.github.mizosoft.methanol.HeadersAccumulator
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder
import java.net.http.HttpHeaders

/** A [spec][Spec] for configuring [Headers][com.github.mizosoft.methanol.kotlin.Headers]. */
@Spec
interface HeadersSpec : StringNameMultiStringValueSpec {
  infix fun String.onlyTo(value: String)

  infix fun String.onlyTo(values: List<String>)

  infix fun String.onlyToIfAbsent(value: String)

  infix fun String.onlyToIfAbsent(values: List<String>)
}

internal open class HeadersAccumulatorSpec(private val headers: HeadersAccumulator<*>) :
  HeadersSpec {
  override fun String.to(value: String) {
    headers.header(this, value)
  }

  override fun String.to(values: List<String>) {
    values.forEach { headers.header(this, it) }
  }

  override fun String.onlyTo(value: String) {
    headers.setHeader(this, value)
  }

  override fun String.onlyTo(values: List<String>) {
    headers.setHeader(this, values)
  }

  override fun String.onlyToIfAbsent(value: String) {
    headers.setHeaderIfAbsent(this, value)
  }

  override fun String.onlyToIfAbsent(values: List<String>) {
    headers.setHeaderIfAbsent(this, values)
  }
}

private class HeadersFactorySpec(val builder: HeadersBuilder = HeadersBuilder()) :
  HeadersAccumulatorSpec(builder.asHeadersAccumulator()), FactorySpec<Headers> {
  override fun make(): Headers = builder.build()
}

typealias Headers = HttpHeaders

/** Creates a new [Headers][com.github.mizosoft.methanol.kotlin.Headers] as configured by the given spec block. */
@Suppress("FunctionName")
fun Headers(block: HeadersSpec.() -> Unit) = HeadersFactorySpec().apply(block).make()

/** Creates a copy of the given headers after configuring with the given spec block. */
@Suppress("FunctionName")
fun Headers(headers: Headers, block: HeadersSpec.() -> Unit) =
  HeadersFactorySpec(HeadersBuilder().also { it.addAll(headers) }).apply(block).make()
