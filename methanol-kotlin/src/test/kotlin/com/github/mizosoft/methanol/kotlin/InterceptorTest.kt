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
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import com.github.mizosoft.methanol.ResponseBuilder
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import kotlin.coroutines.coroutineContext
import kotlin.test.Test

class InterceptorTest {
  @Test
  fun interceptorRetainsCoroutineContext() {
    var interceptorCoroutineName: CoroutineName? = null
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +object : Interceptor {
          override suspend fun <T> intercept(
            request: Request,
            chain: Interceptor.Chain<T>
          ): Response<T> {
            interceptorCoroutineName = coroutineContext[CoroutineName]
            return ResponseBuilder<T>()
              .statusCode(200)
              .uri(request.uri())
              .request(request)
              .version(HttpVersion.HTTP_1_1)
              .build()
          }
        }
      }
    }

    runBlocking(CoroutineName("test")) {
      client.get<Unit>("https://example.com")
    }
    assertThat(interceptorCoroutineName).isNotNull().isEqualTo(CoroutineName("test"))
  }

  @Test
  fun backendInterceptorRetainsCoroutineContext() {
    var interceptorCoroutineName: CoroutineName? = null
    val client = Client {
      adapterCodec {
        basic()
      }
      backendInterceptors {
        +object : Interceptor {
          override suspend fun <T> intercept(
            request: Request,
            chain: Interceptor.Chain<T>
          ): Response<T> {
            interceptorCoroutineName = coroutineContext[CoroutineName]
            return ResponseBuilder<T>()
              .statusCode(200)
              .uri(request.uri())
              .request(request)
              .version(HttpVersion.HTTP_1_1)
              .build()
          }
        }
      }
    }

    runBlocking(CoroutineName("test")) {
      client.get<Unit>("https://example.com")
    }
    assertThat(interceptorCoroutineName).isNotNull().isEqualTo(CoroutineName("test"))
  }

  @Test
  fun cancellationIsPropagatedToInterceptors() {
    val channel = Channel<Unit>(1)
    val cancelParentJob = CountDownLatch(1)
    val channelReceiveJob = CompletableFuture<Job>()
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +object : Interceptor {
          override suspend fun <T> intercept(
            request: Request,
            chain: Interceptor.Chain<T>
          ): Response<T> {
            coroutineScope {
              cancelParentJob.countDown()
              channelReceiveJob.complete(launch { channel.receive() })
            }
            return ResponseBuilder<T>()
              .statusCode(200)
              .uri(request.uri())
              .request(request)
              .version(HttpVersion.HTTP_1_1)
              .build()
          }
        }
      }
    }

    runBlocking {
      val job = launch(Dispatchers.Default) {
        client.get<Unit>("https://example.com")
      }
      cancelParentJob.await()
      job.cancel()
      channel.send(Unit)

      // Interceptor work is cancelled by cancelling the main job.
      assertThat(channelReceiveJob.await().isCancelled).isTrue()
    }
  }

  @Test
  fun cancellationIsPropagatedToBackendInterceptors() {
    val channel = Channel<Unit>(1)
    val cancelParentJob = CountDownLatch(1)
    val channelReceiveJob = CompletableFuture<Job>()
    val client = Client {
      adapterCodec {
        basic()
      }
      backendInterceptors {
        +object : Interceptor {
          override suspend fun <T> intercept(
            request: Request,
            chain: Interceptor.Chain<T>
          ): Response<T> {
            coroutineScope {
              cancelParentJob.countDown()
              channelReceiveJob.complete(launch { channel.receive() })
            }
            return ResponseBuilder<T>()
              .statusCode(200)
              .uri(request.uri())
              .request(request)
              .version(HttpVersion.HTTP_1_1)
              .build()
          }
        }
      }
    }

    runBlocking {
      val job = launch(Dispatchers.Default) {
        client.get<Unit>("https://example.com")
      }
      cancelParentJob.await()
      job.cancel()
      channel.send(Unit)

      // Interceptor work is cancelled by cancelling the main job.
      assertThat(channelReceiveJob.await().isCancelled).isTrue()
    }
  }

  @Test
  fun coroutineNameIsRetainedWhenFetchingRequest() {
    var interceptorCoroutineName: CoroutineName? = null
    val client = Client {
      adapterCodec {
        basic()
      }
      interceptors {
        +object : Interceptor {
          override suspend fun <T> intercept(
            request: Request,
            chain: Interceptor.Chain<T>
          ): Response<T> {
            interceptorCoroutineName = coroutineContext[CoroutineName]
            return ResponseBuilder<T>()
              .statusCode(200)
              .uri(request.uri())
              .request(request)
              .version(HttpVersion.HTTP_1_1)
              .build()
          }
        }
      }
    }

    runBlocking(CoroutineName("test")) {
      client.fetch<Unit>(Request {
        uri("https://example.com.")
      })
    }
    assertThat(interceptorCoroutineName).isNotNull().isEqualTo(CoroutineName("test"))
  }
}
