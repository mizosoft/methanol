package com.github.mizosoft.methanol.samples.kotlin

import com.github.mizosoft.methanol.kotlin.Client
import com.github.mizosoft.methanol.kotlin.Interceptor
import com.github.mizosoft.methanol.kotlin.Request
import com.github.mizosoft.methanol.kotlin.Response
import com.github.mizosoft.methanol.kotlin.get
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext

object Coroutines {
  val client = Client {
    interceptors {
      +object : Interceptor {
        override suspend fun <T> intercept(
          request: Request,
          chain: Interceptor.Chain<T>
        ): Response<T> {
          println("Calling Interceptor::intercept with CoroutineName: ${coroutineContext[CoroutineName]}")
          return try {
            delay(1000L)
            chain.forward(request)
          } catch (e: CancellationException) {
            println("Cancelled")
            throw e
          }
        }
      }
    }

    adapterCodec {
      basic()
    }
  }

  fun run() {
    runBlocking(CoroutineName("MyCoroutine")) {
      val deferred = async {
        client.get<String>("https://httpbin.org/get")
      }
      delay(500)
      deferred.cancel()
    }
  }
}
