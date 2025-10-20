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

import com.github.mizosoft.methanol.HeadersAccumulator
import com.github.mizosoft.methanol.internal.extensions.HeadersBuilder
import java.net.http.HttpHeaders

/** A [spec][Spec] for configuring [Headers][com.github.mizosoft.methanol.kotlin.Headers]. */
@Spec
interface HeadersSpec : StringNameMultiStringValueSpec {
  /** Sets the header with this name to the given value. */
  infix fun String.onlyTo(value: String)

  /** Sets the header with this name to the given values. */
  infix fun String.onlyTo(values: List<String>)

  /** Sets the header with this name to the given value only if there's no header with this name. */
  infix fun String.onlyToIfAbsent(value: String)

  /** Sets the header with this name to the given values only if there's no header with this name. */
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

/** Returns all header values associated with the given name. */
operator fun Headers.get(name: String): List<String> = allValues(name)

/**
 * Returns a string representation for this [Headers] that is similar to how it would appear in an
 * HTTP/1.1 response. [valueToString] can be used to control how header values appear, or whether
 * they appear at all, in the returned string. You can use this to hide/exclude sensitive headers.
 */
fun Headers.toHttpString(
  valueToString: (String, String) -> String? = { name, value -> value }
) = buildString {
  for ((name, values) in map()) {
    for (value in values) {
      valueToString(name, value)?.let {
        appendLine("$name: $it")
      }
    }
  }
}
