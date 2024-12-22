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
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import kotlin.test.Test

class HeadersTest {
  @Test
  fun addHeaders() {
    val headers = Headers {
      "X" to "A"
      "X" to "B"
      "Y" to listOf("A", "B")
    }
    assertThat(headers.map()).containsOnly(
      "X" to listOf("A", "B"),
      "Y" to listOf("A", "B")
    )
  }

  @Test
  fun setHeaders() {
    val headers = Headers {
      "X" to "A"
      "Y" to listOf("A", "B")
      "X" onlyTo listOf("C", "D")
      "Y" onlyTo "C"
      "Z" to "A"
    }
    assertThat(headers.map()).containsOnly(
      "X" to listOf("C", "D"),
      "Y" to listOf("C"),
      "Z" to listOf("A")
    )
  }

  @Test
  fun modifyHeaders() {
    val headers = Headers(Headers {
      "X" to "A"
      "Y" to listOf("A", "B")
    }) {
      "Z" to "A"
      "Y" onlyTo "A"
    }
    assertThat(headers.map()).containsOnly(
      "X" to listOf("A"),
      "Y" to listOf("A"),
      "Z" to listOf("A")
    )
  }

  @Test
  fun setHeadersIfAbsent() {
    val headers = Headers(Headers {
      "X" to "A"
      "Y" to listOf("A", "B")
    }) {
      "Y" onlyToIfAbsent "A"
      "Z" onlyToIfAbsent "A"
    }
    assertThat(headers.map()).containsOnly(
      "X" to listOf("A"),
      "Y" to listOf("A", "B"),
      "Z" to listOf("A")
    )
  }

  @Test
  fun getHeaderValues() {
    var headers = Headers {
      "X" to listOf("A", "B")
    }
    assertThat(headers["X"]).containsOnly("A", "B")
    assertThat(headers["Y"]).isEmpty()
  }

  @Test
  fun httpString() {
    var headers = Headers {
      "X" to listOf("A", "B")
      "Y" to listOf("A", "B")
    }
    assertThat(headers.toHttpString()).isEqualTo(
      """
      X: A
      X: B
      Y: A
      Y: B${System.lineSeparator()}
    """.trimIndent()
    )
    assertThat(
      headers.toHttpString(
        valueToString = { name, value ->
          when (name) {
            "X" -> {
              when (value) {
                "B" -> null
                else -> value
              }
            }

            "Y" -> {
              when (value) {
                "B" -> "***"
                else -> value
              }
            }

            else -> value
          }
        }
      )).isEqualTo(
      """
      X: A
      Y: A
      Y: ***${System.lineSeparator()}
    """.trimIndent()
    )
  }
}
