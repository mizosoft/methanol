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
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.prop
import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.MimeBodyPublisher
import org.junit.jupiter.api.Test
import java.net.URI

class RequestExtensionsTest {
  @Test
  fun toTaggableRequest() {
    val tag = Any()
    val request = Request {
      tags {
        +tag
      }
    } as Request
    assertThat(request.toTaggableRequest().tagOf<Any>()).isSameInstanceAs(tag)
    assertThat(
      Request.newBuilder(URI.create("https://example.com")).build().toTaggableRequest().tagOf<Any>()
    ).isNull()
  }

  @Test
  fun withMediaType() {
    assertThat(BodyPublishers.ofString("Hello").withMediaType(MediaType.TEXT_PLAIN))
      .isInstanceOf(MimeBodyPublisher::class)
      .prop(MimeBodyPublisher::mediaType)
      .isEqualTo(MediaType.TEXT_PLAIN)
  }
}
