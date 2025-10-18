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

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.first
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import com.github.mizosoft.methanol.HttpRetriesExhaustedException
import com.github.mizosoft.methanol.HttpRetryTimeoutException
import com.github.mizosoft.methanol.HttpStatus
import com.github.mizosoft.methanol.testing.MockWebServerExtension
import com.github.mizosoft.methanol.testing.TestUtils
import com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.zip.ZipException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

@ExtendWith(MockWebServerExtension::class)
class RetryTest(private val server: MockWebServer) {
  private val serverUri = server.url("/").toString()

  @Test
  fun retryOnException() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onException<ZipException>()
        }
      }
    }

    server.enqueue(
      MockResponse.Builder()
        .body(okio.Buffer().write(ByteArray(128) { 0 }))
        .addHeader("Content-Encoding", "gzip")
        .build()
    )
    server.enqueue(
      MockResponse.Builder()
        .body(okio.Buffer().write("Pikachu".gzipped()))
        .addHeader("Content-Encoding", "gzip")
        .build()
    )

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")
  }

  @Test
  fun retryOnExceptionWithRequestModification() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onException<ZipException> {
            Request(request()) {
              headers {
                "X-Retry" to "true"
              }
            }
          }
        }
      }
    }

    server.enqueue(
      MockResponse.Builder()
        .body(okio.Buffer().write(ByteArray(128) { 0 }))
        .addHeader("Content-Encoding", "gzip")
        .build()
    )
    server.enqueue(
      MockResponse.Builder()
        .body(okio.Buffer().write("Pikachu".gzipped()))
        .addHeader("Content-Encoding", "gzip")
        .build()
    )

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")

    server.takeRequest() // Skip first request.
    assertThat(server.takeRequest().headers["X-Retry"]).isEqualTo("true")
  }

  @Test
  fun retryOnExceptionPredicate() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onException {
            it is ZipException
          }
        }
      }
    }

    server.enqueue(
      MockResponse.Builder()
        .body(okio.Buffer().write(ByteArray(128) { 0 }))
        .addHeader("Content-Encoding", "gzip")
        .build()
    )
    server.enqueue(
      MockResponse.Builder()
        .body(okio.Buffer().write("Pikachu".gzipped()))
        .addHeader("Content-Encoding", "gzip")
        .build()
    )

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")
  }

  @Test
  fun retryOnExceptionPredicateWithRequestModification() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onException({
            it is ZipException
          }) {
            Request(request()) {
              headers {
                "X-Retry" to "true"
              }
            }
          }
        }
      }
    }

    server.enqueue(
      MockResponse.Builder()
        .body(okio.Buffer().write(ByteArray(128) { 0 }))
        .addHeader("Content-Encoding", "gzip")
        .build()
    )
    server.enqueue(
      MockResponse.Builder()
        .body(okio.Buffer().write("Pikachu".gzipped()))
        .addHeader("Content-Encoding", "gzip")
        .build()
    )

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")

    server.takeRequest() // Skip first request.
    assertThat(server.takeRequest().headers["X-Retry"]).isEqualTo("true")
  }

  @Test
  fun retryOnStatus() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onStatus(500)
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(body = "Pikachu"))

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")
  }

  @Test
  fun retryOnStatusWithRequestModification() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onStatus(setOf(500)) {
            Request(request()) {
              headers {
                "X-Retry" to "true"
              }
            }
          }
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(body = "Pikachu"))

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")

    server.takeRequest() // Skip first request.
    assertThat(server.takeRequest().headers["X-Retry"]).isEqualTo("true")
  }

  @Test
  fun retryOnStatusPredicate() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onStatus(HttpStatus::isServerError)
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(body = "Pikachu"))

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")
  }

  @Test
  fun retryOnStatusPredicateModification() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onStatus(HttpStatus::isServerError) {
            Request(request()) {
              headers {
                "X-Retry" to "true"
              }
            }
          }
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(body = "Pikachu"))

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")

    server.takeRequest() // Skip first request.
    assertThat(server.takeRequest().headers["X-Retry"]).isEqualTo("true")
  }

  @Test
  fun retryOnResponsePredicate() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onResponse(HttpStatus::isServerError)
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(body = "Pikachu"))

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")
  }

  @Test
  fun retryOnResponsePredicateWithRequestModification() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onResponse(HttpStatus::isServerError) {
            Request(request()) {
              headers {
                "X-Retry" to "true"
              }
            }
          }
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(body = "Pikachu"))

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")

    server.takeRequest() // Skip first request.
    assertThat(server.takeRequest().headers["X-Retry"]).isEqualTo("true")
  }

  @Test
  fun onContext() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          on({ it.response().map(HttpStatus::isServerError).orElse(false) })
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(body = "Pikachu"))

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")
  }

  @Test
  fun onContextWihRequestModification() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          on({ it.response().map(HttpStatus::isServerError).orElse(false) }) {
            Request(request()) {
              headers {
                "X-Retry" to "true"
              }
            }
          }
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(body = "Pikachu"))

    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasBody("Pikachu")

    server.takeRequest() // Skip first request.
    assertThat(server.takeRequest().headers["X-Retry"]).isEqualTo("true")
  }

  @Test
  fun exhaustRetries() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          on({ it.response().map(HttpStatus::isServerError).orElse(false) }) {
            Request(request()) {
              headers {
                "X-Retry" to "true"
              }
            }
          }
          throwOnExhaustion()
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(code = 500))

    assertFailure {
      runBlocking {
        client.get<String>(serverUri)
      }
    }.isInstanceOf(HttpRetriesExhaustedException::class.java)
  }

  @Test
  fun retryTimeout() {
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          on({ it.response().map(HttpStatus::isServerError).orElse(false) }) {
            Request(request()) {
              headers {
                "X-Retry" to "true"
              }
            }
          }
          timeout(1.nanoseconds)
          throwOnExhaustion()
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(code = 500))

    assertFailure {
      runBlocking {
        client.get<String>(serverUri)
      }
    }.isInstanceOf(HttpRetryTimeoutException::class.java)
  }

  private sealed interface RetryEvent

  private class FirstAttempt(val request: Request) : RetryEvent

  private class Retry(val context: RetryContext<*>, val nextRequest: Request, val delay: Duration) : RetryEvent

  private class Timeout(val context: RetryContext<*>) : RetryEvent

  private class Complete(val context: RetryContext<*>) : RetryEvent

  private class Exhaustion(val context: RetryContext<*>) : RetryEvent

  private fun MutableList<RetryEvent>.listener(): RetryListener = RetryListener {
    onFirstAttempt {
      add(FirstAttempt(it))
    }
    onRetry { ctx, nextRequest, delay ->
      add(Retry(ctx, nextRequest, delay))
    }
    onTimeout {
      add(Timeout(it))
    }
    onExhaustion {
      add(Exhaustion(it))
    }
    onComplete {
      add(Complete(it))
    }
  }

  @Test
  fun retryListenerCompletion() {
    val events = mutableListOf<RetryEvent>()
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onStatus(setOf(500)) {
            Request(request()) {
              headers {
                "X-Retry" to "true"
              }
            }
          }
          listener(events.listener())
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(code = 500))

    runBlocking {
      client.get<String>(serverUri)
    }

    assertThat(events).first().isInstanceOf(FirstAttempt::class).given { firstAttempt ->
      verifyThat(firstAttempt.request).hasUri(serverUri)
    }
    events.removeFirst()
    assertThat(events).first().isInstanceOf(Retry::class).given { retry ->
      verifyThat(retry.context.request()).hasUri(serverUri)
      verifyThat(retry.nextRequest).hasUri(serverUri).containsHeaderExactly("X-Retry", "true")
      assertThat(retry.delay).isEqualTo(Duration.ZERO)
    }
    events.removeFirst()
    assertThat(events).first().isInstanceOf(Complete::class).given { complete ->
      verifyThat(complete.context.request()).hasUri(serverUri)
    }
  }

  @Test
  fun retryListenerExhaustion() {
    val events = mutableListOf<RetryEvent>()
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onStatus(setOf(500)) {
            Request(request()) {
              headers {
                "X-Retry" to "true"
              }
            }
          }
          throwOnExhaustion()
          listener(events.listener())
        }
      }
    }

    server.enqueue(MockResponse(code = 500))
    server.enqueue(MockResponse(code = 500))

    assertFailure {
      runBlocking {
        client.get<String>(serverUri)
      }
    }.isInstanceOf(HttpRetriesExhaustedException::class.java)

    assertThat(events).first().isInstanceOf(FirstAttempt::class).given { firstAttempt ->
      verifyThat(firstAttempt.request).hasUri(serverUri)
    }
    events.removeFirst()
    assertThat(events).first().isInstanceOf(Retry::class).given { retry ->
      verifyThat(retry.context.request()).hasUri(serverUri)
      verifyThat(retry.nextRequest).hasUri(serverUri).containsHeaderExactly("X-Retry", "true")
      assertThat(retry.delay).isEqualTo(Duration.ZERO)
    }
    events.removeFirst()
    assertThat(events).first().isInstanceOf(Exhaustion::class).given { complete ->
      verifyThat(complete.context.request()).hasUri(serverUri)
    }
  }

  @Test
  fun retryListenerTimeout() {
    val events = mutableListOf<RetryEvent>()
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +RetryInterceptor {
          maxRetries(1)
          onStatus(setOf(500)) {
            Request(request()) {
              headers {
                "X-Retry" to "true"
              }
            }
          }
          listener(events.listener())

          // Timeout by applying a too-long delay.
          timeout(TestUtils.TIMEOUT_SECONDS.seconds)
          backoff(BackoffStrategy.fixed(1000.seconds))
        }
      }
    }

    server.enqueue(MockResponse(code = 500))

    assertFailure {
      runBlocking {
        client.get<String>(serverUri)
      }
    }.isInstanceOf(HttpRetryTimeoutException::class.java)

    assertThat(events).first().isInstanceOf(FirstAttempt::class).given { firstAttempt ->
      verifyThat(firstAttempt.request).hasUri(serverUri)
    }
    events.removeFirst()
    assertThat(events).first().isInstanceOf(Retry::class).given { retry ->
      verifyThat(retry.context.request()).hasUri(serverUri)
      verifyThat(retry.nextRequest).hasUri(serverUri).containsHeaderExactly("X-Retry", "true")
      assertThat(retry.delay).isEqualTo(1000.seconds)
    }
    events.removeFirst()
    assertThat(events).first().isInstanceOf(Timeout::class).given { timeout ->
      verifyThat(timeout.context.request()).hasUri(serverUri)
    }
  }
}
