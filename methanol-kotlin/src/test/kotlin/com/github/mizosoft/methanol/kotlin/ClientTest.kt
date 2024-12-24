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
import com.github.mizosoft.methanol.AdapterCodec
import com.github.mizosoft.methanol.BodyAdapter.Decoder
import com.github.mizosoft.methanol.BodyAdapter.Encoder
import com.github.mizosoft.methanol.HttpCache
import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.TypeRef
import com.github.mizosoft.methanol.testing.TestUtils
import java.io.IOException
import java.net.*
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class ClientTest {
  @Test
  fun userAgent() {
    val client = Client {
      userAgent("Will Smith")
    }
    assertThat(client.userAgent()).hasValue("Will Smith")
  }

  @Test
  fun baseUriFromString() {
    val client = Client {
      baseUri("https://example.com")
    }
    assertThat(client.baseUri()).hasValue(URI.create("https://example.com"))
  }

  @Test
  fun baseUri() {
    val client = Client {
      baseUri(URI.create("https://example.com"))
    }
    assertThat(client.baseUri()).hasValue(URI.create("https://example.com"))
  }

  @Test
  fun defaultHeaders() {
    val client = Client {
      defaultHeaders {
        "Accept" to "application/json"
        "Accept-Encoding" to listOf("gzip", "deflate")
      }
    }
    assertThat(client.defaultHeaders().map()).containsOnly(
      "Accept" to listOf("application/json"),
      "Accept-Encoding" to listOf("gzip", "deflate")
    )
  }

  @Test
  fun requestTimeout() {
    val client = Client {
      requestTimeout(1.seconds)
    }
    assertThat(client.requestTimeout()).hasValue(1.seconds.toJavaDuration())
  }

  @Test
  fun headersTimeout() {
    val client = Client {
      headersTimeout(1.seconds)
    }
    assertThat(client.headersTimeout()).hasValue(1.seconds.toJavaDuration())
  }

  @Test
  fun readTimeout() {
    val client = Client {
      readTimeout(1.seconds)
    }
    assertThat(client.readTimeout()).hasValue(1.seconds.toJavaDuration())
  }

  @Test
  fun adapterCodec() {
    val client = Client {
      adapterCodec(
        AdapterCodec.newBuilder()
          .encoder(object : Encoder {
            override fun isCompatibleWith(mediaType: MediaType) = TODO("Not yet implemented")
            override fun supportsType(type: TypeRef<*>) = TODO("Not yet implemented")
            override fun toBody(`object`: Any, mediaType: MediaType?) = TODO("Not yet implemented")
          })
          .decoder(object : Decoder {
            override fun isCompatibleWith(mediaType: MediaType) = TODO("Not yet implemented")
            override fun supportsType(type: TypeRef<*>?) = TODO("Not yet implemented")
            override fun <T : Any?> toObject(
              objectType: TypeRef<T>,
              mediaType: MediaType?
            ) = TODO("Not yet implemented")
          }).basic().build()
      )
    }
    assertThat(client.adapterCodec()).isPresent().all {
      prop(AdapterCodec::encoders).hasSize(2)
      prop(AdapterCodec::decoders).hasSize(2)
    }
  }

  @Test
  fun adapterCodecFromSpec() {
    val client = Client {
      adapterCodec {
        +object : Encoder {
          override fun isCompatibleWith(mediaType: MediaType) = TODO("Not yet implemented")
          override fun supportsType(type: TypeRef<*>) = TODO("Not yet implemented")
          override fun toBody(`object`: Any, mediaType: MediaType?) = TODO("Not yet implemented")
        }
        +object : Decoder {
          override fun isCompatibleWith(mediaType: MediaType) = TODO("Not yet implemented")
          override fun supportsType(type: TypeRef<*>?) = TODO("Not yet implemented")
          override fun <T : Any?> toObject(
            objectType: TypeRef<T>,
            mediaType: MediaType?
          ) = TODO("Not yet implemented")
        }
        basic()
      }
    }
    assertThat(client.adapterCodec()).isPresent().all {
      prop(AdapterCodec::encoders).hasSize(2)
      prop(AdapterCodec::decoders).hasSize(2)
    }
  }

  @Test
  fun autoAcceptEncoding() {
    val client = Client {
      autoAcceptEncoding(true)
    }
    assertThat(client.autoAcceptEncoding()).isTrue()
  }

  @Test
  fun interceptors() {
    val client = Client {
      interceptors {
        +object : Interceptor {
          override suspend fun <T> intercept(
            request: HttpRequest,
            chain: Interceptor.Chain<T>
          ) = TODO("Not yet implemented")
        }
      }
    }
    assertThat(client.interceptors()).hasSize(1)
  }

  @Test
  fun backendInterceptors() {
    val client = Client {
      backendInterceptors {
        +object : Interceptor {
          override suspend fun <T> intercept(
            request: HttpRequest,
            chain: Interceptor.Chain<T>
          ) = TODO("Not yet implemented")
        }
      }
    }
    assertThat(client.backendInterceptors()).hasSize(1)
  }

  @Test
  fun cache() {
    val client = Client {
      cache {
        inMemory(1024 * 1024)
      }
    }
    assertThat(client.caches()).all {
      hasSize(1)
      first().prop(HttpCache::maxSize).isEqualTo(1024 * 1024)
    }
  }

  @Test
  fun cacheChain() {
    val client = Client {
      cacheChain {
        +Cache {
          inMemory(1024 * 1024)
        }
        +Cache {
          inMemory(2 * 1024 * 1024)
        }
      }
    }
    assertThat(client.caches()).all {
      hasSize(2)
      first().prop(HttpCache::maxSize).isEqualTo(1024 * 1024)
      index(1).prop(HttpCache::maxSize).isEqualTo(2 * 1024 * 1024)
    }
  }

  @Test
  fun cookieHandler() {
    val cookieManager = CookieManager()
    val client = Client {
      cookieHandler(cookieManager)
    }
    assertThat(client.cookieHandler()).hasValue(cookieManager)
  }

  @Test
  fun sslContext() {
    val sslContext = TestUtils.localhostSslContext()
    val client = Client {
      sslContext(sslContext)
    }
    assertThat(client.sslContext())
  }

  @Test
  fun executor() {
    val executor = Executor { it.run() }
    val client = Client {
      executor(executor)
    }
    assertThat(client.executor()).hasValue(executor)
  }

  @Test
  fun followRedirects() {
    val client = Client {
      followRedirects(HttpClient.Redirect.ALWAYS)
    }
    assertThat(client.followRedirects()).isEqualTo(HttpClient.Redirect.ALWAYS)
  }

  @Test
  fun version() {
    val client = Client {
      version(HttpClient.Version.HTTP_1_1)
    }
    assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1)
  }

  @Test
  fun proxy() {
    val proxySelector = object : ProxySelector() {
      override fun select(uri: URI?) = TODO("Not yet implemented")
      override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) =
        TODO("Not yet implemented")
    }
    val client = Client {
      proxy(proxySelector)
    }
    assertThat(client.proxy()).hasValue(proxySelector)
  }

  @Test
  fun authenticator() {
    val authenticator = object : Authenticator() {}
    val client = Client {
      authenticator(authenticator)
    }
    assertThat(client.authenticator()).hasValue(authenticator)
  }
}