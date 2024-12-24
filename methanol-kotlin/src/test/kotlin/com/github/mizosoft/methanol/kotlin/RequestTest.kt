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

import assertk.all
import assertk.assertThat
import assertk.assertions.*
import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.MimeBodyPublisher
import java.net.URI
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class RequestTest {
  @Test
  fun uri() {
    val uri = URI.create("https://example.com")
    val request = Request {
      uri(uri)
    }
    assertThat(request.uri()).isEqualTo(uri)
  }

  @Test
  fun uriFromString() {
    val uri = "https://example.com"
    val request = Request {
      uri(uri)
    }
    assertThat(request.uri()).isEqualTo(URI.create(uri))
  }

  @Test
  fun headers() {
    val request = Request {
      headers {
        "X" to "A"
        "X" to "B"
        "Y" to listOf("A", "B")
        "Z" to "A"
      }
    }
    assertThat(request.headers().map()).containsOnly(
      "X" to listOf("A", "B"),
      "Y" to listOf("A", "B"),
      "Z" to listOf("A")
    )
  }

  @Test
  fun headersReplace() {
    val request = Request(
      Request {
        headers {
          "X" to listOf("A", "B")
          "Y" to listOf("A", "B", "C")
          "Z" to listOf("A")
        }
      }
    ) {
      headers {
        "X" onlyTo "A"
        "Y" onlyTo listOf("A", "B")
      }
    }
    assertThat(request.headers().map()).containsOnly(
      "X" to listOf("A"),
      "Y" to listOf("A", "B"),
      "Z" to listOf("A")
    )
  }

  @Test
  fun cacheControl() {
    val request = Request {
      cacheControl {
        maxAge(1.seconds)
      }
    }
    assertThat(request.headers().map()).containsOnly(
      "Cache-Control" to listOf("max-age=1")
    )
  }

  @Test
  fun adapterCodec() {
    val request = Request {
      adapterCodec {
        basic()
      }
      POST {
        body("a", MediaType.TEXT_PLAIN)
      }
    }
    assertThat(request.method()).isEqualTo("POST")
    assertThat(request.bodyPublisher().orElseThrow().readString()).isEqualTo("a")
  }

  @Test
  fun adapterCodecFromValue() {
    val request = Request {
      adapterCodec(AdapterCodec {
        basic()
      })
      POST {
        body("a", MediaType.TEXT_PLAIN)
      }
    }
    assertThat(request.method()).isEqualTo("POST")
    assertThat(request.bodyPublisher().orElseThrow().readString()).isEqualTo("a")
  }

  @Test
  fun expectContinue() {
    val request = Request {
      expectContinue(true)
    }
    assertThat(request.expectContinue()).isTrue()
  }

  @Test
  fun version() {
    val request = Request {
      version(HttpVersion.HTTP_1_1)
    }
    assertThat(request.version()).hasValue(HttpVersion.HTTP_1_1)
  }

  @Test
  fun timeout() {
    val request = Request {
      timeout(1.seconds)
    }
    assertThat(request.timeout()).hasValue(1.seconds.toJavaDuration())
  }

  object MyTag

  @Test
  fun tags() {
    val request = Request {
      tags {
        +MyTag
        +listOf(MyTag)
      }
    }
    assertThat(request.tag<MyTag>()).isEqualTo(MyTag)
    assertThat(request.tag<List<MyTag>>()).isEqualTo(listOf(MyTag))
  }

  @Test
  fun requestWithGet() {
    // Make sure that GET() actually sets the method to GET through modifying a POST request as GET
    // Is the default.
    val request = Request(Request {
      POST {
        body(BodyPublishers.noBody())
      }
    }) {
      GET()
    }
    assertThat(request.method()).isEqualTo("GET")
    assertThat(request.bodyPublisher()).isEmpty()
  }

  @Test
  fun requestWithDelete() {
    // Make sure that GET() actually sets the method to GET through modifying a POST request as GET
    // Is the default.
    val request = Request {
      DELETE()
    }
    assertThat(request.method()).isEqualTo("DELETE")
    assertThat(request.bodyPublisher()).isEmpty()
  }

  @Test
  fun requestWithPostAndFormBody() {
    val request = Request {
      POST {
        formBody {
          "x" to "a"
        }
      }
    }
    assertThat(request.method()).isEqualTo("POST")
    assertThat(request.bodyPublisher()).isPresent().isInstanceOf(FormBody::class)
      .prop(FormBody::queries).containsOnly("x" to listOf("a"))
  }

  @Test
  fun requestWithPostAndMultipartBody() {
    val request = Request {
      POST {
        multipartBody {
          "x" to "a"
        }
      }
    }
    assertThat(request.method()).isEqualTo("POST")
    assertThat(request.bodyPublisher()).isPresent().isInstanceOf(MultipartBody::class)
      .prop(MultipartBody::parts).all {
        hasSize(1)
        first().given {
          assertThat(it.bodyPublisher().readString()).isEqualTo("a")
          assertThat(it.headers().map()).containsOnly(
            "Content-Disposition" to listOf("form-data; name=\"x\"")
          )
        }
      }
  }

  @Test
  fun requestWithPut() {
    val request = Request {
      PUT {
        body(BodyPublishers.ofString("a"))
      }
    }
    assertThat(request.method()).isEqualTo("PUT")
    assertThat(request.bodyPublisher()).isPresent()
  }

  @Test
  fun requestWithPatch() {
    val request = Request {
      PATCH {
        body(BodyPublishers.ofString("a"))
      }
    }
    assertThat(request.method()).isEqualTo("PATCH")
    assertThat(request.bodyPublisher()).isPresent()
  }

  @Test
  fun requestWithCustomMethod() {
    val request = Request {
      method("DROP") { }
    }
    assertThat(request.method()).isEqualTo("DROP")
    assertThat(request.bodyPublisher()).isPresent().given {
      assertThat(it.readString()).isEqualTo("")
    }
  }

  @Test
  fun requestWithCustomMethodWithBody() {
    val request = Request {
      method("LIFT") {
        body(BodyPublishers.ofString("a"))
      }
    }
    assertThat(request.method()).isEqualTo("LIFT")
    assertThat(request.bodyPublisher()).isPresent().given {
      assertThat(it.readString()).isEqualTo("a")
    }
  }

  @Test
  fun toTaggableRequest() {
    val tag = Any()
    val request = Request {
      tags {
        +tag
      }
    } as Request
    assertThat(request.toTaggableRequest().tag<Any>()).isSameInstanceAs(tag)
    assertThat(
      Request.newBuilder(URI.create("https://example.com")).build().toTaggableRequest().tag<Any>()
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

