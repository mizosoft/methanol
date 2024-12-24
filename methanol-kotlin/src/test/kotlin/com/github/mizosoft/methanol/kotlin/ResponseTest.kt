/*
 * Copyright (c) 2024 Moataz Hussein
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
import assertk.assertions.isTrue
import com.github.mizosoft.methanol.ResponseBuilder
import java.net.URI
import kotlin.test.Test

class ResponseTest {
  private val responseBuilder =
    ResponseBuilder.create<Unit>()
      .uri(URI.create("https://example.com"))
      .request(Request {
        uri("https://example.com")
      })
      .version(HttpVersion.HTTP_1_1)


  @Test
  fun informationalResponseIsInformational() {
    assertThat(responseBuilder.statusCode(100).build().isInformational()).isTrue()
  }

  @Test
  fun successfulResponseIsSuccessful() {
    assertThat(responseBuilder.statusCode(200).build().isSuccessful()).isTrue()
  }

  @Test
  fun redirectionResponseIsRedirection() {
    assertThat(responseBuilder.statusCode(300).build().isRedirection()).isTrue()
  }

  @Test
  fun clientErrorResponseIsClientError() {
    assertThat(responseBuilder.statusCode(400).build().isClientError()).isTrue()
  }

  @Test
  fun serverErrorResponseIsServerError() {
    assertThat(responseBuilder.statusCode(500).build().isServerError()).isTrue()
  }
}
