package com.github.mizosoft.methanol.kotlin

import com.github.mizosoft.methanol.CacheControl
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/** A [spec][Spec] for configuring [CacheControl] instances. */
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
