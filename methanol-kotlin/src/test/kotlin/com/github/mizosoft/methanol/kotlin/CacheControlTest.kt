/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import assertk.assertThat
import assertk.assertions.containsOnly
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class CacheControlTest {
  @Test
  fun createCacheControl() {
    val cacheControl = CacheControl {
      maxAge(1.seconds)
      minFresh(2.seconds)
      maxStale(3.seconds)
      staleIfError(4.seconds)
      noCache()
      noStore()
      noTransform()
      onlyIfCached()
      +"my-directive"
      "my-directive-with-value" to "my-value"
    }
    assertThat(cacheControl.directives()).containsOnly(
      "max-age" to "1",
      "min-fresh" to "2",
      "max-stale" to "3",
      "stale-if-error" to "4",
      "no-cache" to "",
      "no-store" to "",
      "no-transform" to "",
      "only-if-cached" to "",
      "my-directive" to "",
      "my-directive-with-value" to "my-value"
    )
  }

  @Test
  fun anyMaxStale() {
    val cacheControl = CacheControl {
      anyMaxStale()
    }
    assertThat(cacheControl.directives()).containsOnly("max-stale" to "")
  }
}