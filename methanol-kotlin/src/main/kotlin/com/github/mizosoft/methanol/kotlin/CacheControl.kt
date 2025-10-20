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

import com.github.mizosoft.methanol.CacheControl
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * A [spec][Spec] for configuring [CacheControl] instances.
 *
 * See [CacheControl.Builder].
 */
@Spec
interface CacheControlSpec {
  fun maxAge(maxAge: Duration)

  fun minFresh(minFresh: Duration)

  fun maxStale(maxStale: Duration)

  fun anyMaxStale()

  fun staleIfError(staleIfError: Duration)

  fun noCache()

  fun noStore()

  fun noTransform()

  fun onlyIfCached()

  operator fun String.unaryPlus()

  infix fun String.to(value: String)
}

private class CacheControlFactorySpec(
  private val builder: CacheControl.Builder = CacheControl.newBuilder()
) : CacheControlSpec, FactorySpec<CacheControl> {
  override fun maxAge(maxAge: Duration) {
    builder.maxAge(maxAge.toJavaDuration())
  }

  override fun minFresh(minFresh: Duration) {
    builder.minFresh(minFresh.toJavaDuration())
  }

  override fun maxStale(maxStale: Duration) {
    builder.maxStale(maxStale.toJavaDuration())
  }

  override fun anyMaxStale() {
    builder.anyMaxStale()
  }

  override fun staleIfError(staleIfError: Duration) {
    builder.staleIfError(staleIfError.toJavaDuration())
  }

  override fun noCache() {
    builder.noCache()
  }

  override fun noStore() {
    builder.noStore()
  }

  override fun noTransform() {
    builder.noTransform()
  }

  override fun onlyIfCached() {
    builder.onlyIfCached()
  }

  override operator fun String.unaryPlus() {
    builder.directive(this)
  }

  override infix fun String.to(value: String) {
    builder.directive(this, value)
  }

  override fun make(): CacheControl = builder.build()
}

/** Creates a new [CacheControl][com.github.mizosoft.methanol.CacheControl] as configured by the given spec block. */
fun CacheControl(block: CacheControlSpec.() -> Unit) = CacheControlFactorySpec().apply(block).make()

/** Parses the given string into a [CacheControl] instance as specified by the `Cache-Control` header. */
fun String.toCacheControl(): CacheControl = CacheControl.parse(this)

/** Parses the given strings into a [CacheControl] instance as specified by the `Cache-Control` header. */
fun List<String>.toCacheControl(): CacheControl = CacheControl.parse(this)
