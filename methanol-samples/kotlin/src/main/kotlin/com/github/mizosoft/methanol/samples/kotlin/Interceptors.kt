package com.github.mizosoft.methanol.samples.kotlin

import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.kotlin.Client
import com.github.mizosoft.methanol.kotlin.Interceptor
import com.github.mizosoft.methanol.kotlin.Request
import com.github.mizosoft.methanol.kotlin.Response
import com.github.mizosoft.methanol.kotlin.get
import com.github.mizosoft.methanol.kotlin.isSuccessful
import com.github.mizosoft.methanol.kotlin.toHttpString
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

    userAgent("Arnold Schwarzenegger")
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
