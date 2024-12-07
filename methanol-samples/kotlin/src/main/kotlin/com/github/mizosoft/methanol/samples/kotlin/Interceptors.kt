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

package com.github.mizosoft.methanol.samples.kotlin

import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.kotlin.*
import java.lang.System.Logger.Level
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds

object Interceptors {
  object LoggingInterceptor : Interceptor {
    val logger: System.Logger = System.getLogger(LoggingInterceptor::class.simpleName)
    val requestIdGenerator = AtomicInteger()

    override suspend fun <T> intercept(
      request: Request,
      chain: Interceptor.Chain<T>
    ): Response<T> {
      val requestId = requestIdGenerator.getAndIncrement()
      val start = System.currentTimeMillis()
      logger.log(Level.INFO) {
        "$requestId: sending $request \n${request.headers().toHttpString()}"
      }

      return chain.forward(request).also { response ->
        logger.log(Level.INFO) {
          "$requestId: received $response in ${(System.currentTimeMillis() - start).milliseconds} \n" +
              request.headers().toHttpString()
        }
        require(response.isSuccessful()) { "Unsuccessful response: $response" }
      }
    }
  }

  val client = Client {
    interceptors {
      +LoggingInterceptor
    }

    userAgent("Dave Bautista")
    adapterCodec {
      basic()
    }
  }

  suspend fun run() {
    client.get<Unit>("https://httpbin.org/gzip") {
      headers {
        "Accept" to MediaType.APPLICATION_OCTET_STREAM.toString()
      }
    }
  }
}
