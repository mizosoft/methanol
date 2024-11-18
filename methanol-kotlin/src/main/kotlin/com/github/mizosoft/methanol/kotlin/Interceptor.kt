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

import com.github.mizosoft.methanol.Methanol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.EmptyCoroutineContext

/**
 * An object that intercepts the request before being sent and the response before being returned.
 * The [intercept] function is a suspending function, meaning it is invoked as a coroutine. The
 * coroutine shares the [kotlin.coroutines.CoroutineContext] used when the HTTP call is first
 * initiated. Typically, this means that all interceptors, along with the HTTP call, are invoked
 * within the same [CoroutineScope], and thus typically the same parent [kotlinx.coroutines.Job].
 */
@Spec
interface Interceptor {
  suspend fun <T> intercept(request: Request, chain: Chain<T>): Response<T>

  /** A continuation of the interceptor chain. */
  interface Chain<T> {
    val bodyHandler: BodyHandler<T>

    val pushPromiseHandler: PushPromiseHandler<T>?

    /** Forwards the given request to the rest of the chain, or to the backend if no other interceptors are to be invoked. */
    suspend fun forward(request: Request): Response<T>

    fun <U> with(
      bodyHandler: BodyHandler<U>,
      pushPromiseHandler: PushPromiseHandler<U>? = null
    ): Chain<U>
  }
}

internal class CoroutineScopeHolder(val scope: CoroutineScope)

internal fun <T> Methanol.Interceptor.Chain<T>.toCoroutineChain(): Interceptor.Chain<T> =
  object : Interceptor.Chain<T> {
    override val bodyHandler: BodyHandler<T>
      get() = this@toCoroutineChain.bodyHandler()

    override val pushPromiseHandler: PushPromiseHandler<T>?
      get() = this@toCoroutineChain.pushPromiseHandler().orElse(null)

    override suspend fun forward(request: Request): Response<T> =
      this@toCoroutineChain.forwardAsync(request).await()

    override fun <U> with(
      bodyHandler: BodyHandler<U>,
      pushPromiseHandler: PushPromiseHandler<U>?
    ) = this@toCoroutineChain.with(bodyHandler, pushPromiseHandler).toCoroutineChain()
  }

internal fun Interceptor.toMethanolInterceptor() = object : Methanol.Interceptor {
  override fun <T : Any?> intercept(
    request: Request,
    chain: Methanol.Interceptor.Chain<T>
  ): Response<T> {
    val coroutineContext =
      request.tagOf<CoroutineScopeHolder>()?.scope?.coroutineContext ?: EmptyCoroutineContext
    return runBlocking(coroutineContext) {
      this@toMethanolInterceptor.intercept(request, chain.toCoroutineChain())
    }
  }

  override fun <T : Any?> interceptAsync(
    request: Request,
    chain: Methanol.Interceptor.Chain<T>
  ): CompletableFuture<Response<T>> {
    val coroutineScope =
      request.tagOf<CoroutineScopeHolder>()?.scope ?: CoroutineScope(Dispatchers.Default)
    return coroutineScope.future {
      this@toMethanolInterceptor.intercept(request, chain.toCoroutineChain())
    }
  }
}
