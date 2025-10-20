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

package com.github.mizosoft.methanol.samples.kotlin

import com.github.mizosoft.methanol.HttpStatus
import com.github.mizosoft.methanol.kotlin.*
import java.net.ConnectException
import java.net.http.HttpTimeoutException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object RetryingClient {
  val client = Client {
    adapterCodec {
      basic()
    }
    connectTimeout(5.seconds)
    requestTimeout(10.seconds)
    interceptors {
      +RetryInterceptor {
        maxRetries(5) // Default is 5
        onException<ConnectException, HttpTimeoutException>()
        onStatus(HttpStatus::isServerError)
        backoff(BackoffStrategy.exponential(100.milliseconds, 15.seconds).withJitter())

        // Only retry selected hosts. Remove if you want to retry all requests.
        onlyIf { request -> request.uri().host.contains("example") }
      }
    }
  }

  suspend fun run() {
    val response = client.get<String>("https://internal.example.com")
    println(response)
  }
}
