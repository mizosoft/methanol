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

import com.github.mizosoft.methanol.Methanol
import com.github.mizosoft.methanol.RetryInterceptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.future.future
import kotlinx.coroutines.runBlocking
import java.net.http.HttpRequest
import java.util.concurrent.CompletableFuture
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/**
 * An object that intercepts requests before being sent and responses before being returned. The
 * [intercept] function is a suspending function, meaning it is invoked as a coroutine. The
 * coroutine shares the [kotlin.coroutines.CoroutineContext] used when the HTTP call is first
 * initiated. Typically, this means that all interceptors, along with the HTTP call, are invoked
 * within the same [CoroutineScope], and thus typically have the same parent [kotlinx.coroutines.Job].
 */
interface Interceptor {

  /** Intercepts the given request and returns the resulting response, typically by invoking [Chain.forward] on the given chain. */
  suspend fun <T> intercept(request: Request, chain: Chain<T>): Response<T>

  /** A continuation of the interceptor chain. */
  interface Chain<T> {
    val bodyHandler: BodyHandler<T>

    val pushPromiseHandler: PushPromiseHandler<T>?

    /** Forwards the given request to the rest of the chain, or to the backend if no other interceptors are to be invoked. */
    suspend fun forward(request: Request): Response<T>

    fun <U> with(
      bodyHandler: BodyHandler<U>, pushPromiseHandler: PushPromiseHandler<U>?
    ): Chain<U>
  }
}

internal class CoroutineScopeHolder(val scope: CoroutineScope)

private class CoroutineChain<T>(val innerChain: Methanol.Interceptor.Chain<T>) : Interceptor.Chain<T> {
  override val bodyHandler: BodyHandler<T>
    get() = innerChain.bodyHandler()

  override val pushPromiseHandler: PushPromiseHandler<T>?
    get() = innerChain.pushPromiseHandler().orElse(null)

  override suspend fun forward(request: Request): Response<T> = innerChain.forwardAsync(request).await()

  override fun <U> with(
    bodyHandler: BodyHandler<U>, pushPromiseHandler: PushPromiseHandler<U>?
  ) = CoroutineChain(innerChain.with(bodyHandler, pushPromiseHandler))
}

internal fun Interceptor.toMethanolInterceptor() = object : Methanol.Interceptor {
  override fun <T> intercept(
    request: Request, chain: Methanol.Interceptor.Chain<T>
  ): Response<T> {
    val coroutineContext = request.tag<CoroutineScopeHolder>()?.scope?.coroutineContext ?: EmptyCoroutineContext
    return runBlocking(coroutineContext) {
      this@toMethanolInterceptor.intercept(request, CoroutineChain(chain))
    }
  }

  override fun <T> interceptAsync(
    request: Request, chain: Methanol.Interceptor.Chain<T>
  ): CompletableFuture<Response<T>> {
    val coroutineScope = request.tag<CoroutineScopeHolder>()?.scope ?: CoroutineScope(Dispatchers.Default)
    return coroutineScope.future {
      this@toMethanolInterceptor.intercept(request, CoroutineChain(chain))
    }
  }
}

internal fun Methanol.Interceptor.toCoroutineInterceptor() = object : Interceptor {
  override suspend fun <T> intercept(
    request: Request, chain: Interceptor.Chain<T>
  ): Response<T> {
    val innerChain = (chain as? CoroutineChain<T>)?.innerChain
      ?: throw IllegalStateException("Unsupported chain type: ${chain.javaClass.name}")
    return interceptAsync(request, innerChain).await()
  }
}

typealias RetryContext<T> = RetryInterceptor.Context<T>

typealias RetryListener = RetryInterceptor.Listener

/** A strategy for backing off (delaying) before a retry. */
fun interface BackoffStrategy {

  /**
   * Returns the [Duration] to wait for before retrying the request for the given retry
   * number.
   */
  fun backoff(context: RetryContext<*>): Duration

  /**
   * Returns a [BackoffStrategy] that applies [full jitter](https://aws.amazon.com/blogs/architecture/exponential-backoff-and-jitter/)
   * to this [BackoffStrategy], where the degree of "fullness" is specified by the given factor.
   */
  fun withJitter(factor: Double = 1.0) = toMethanolBackoffStrategy().withJitter(factor).toKotlinBackoffStrategy()

  companion object {

    /** Returns a [BackoffStrategy] that applies no delays. */
    fun none() = RetryInterceptor.BackoffStrategy.none().toKotlinBackoffStrategy()

    /** Returns a [BackoffStrategy] that applies a fixed delay every retry. */
    fun fixed(delay: Duration) =
      RetryInterceptor.BackoffStrategy.fixed(delay.toJavaDuration()).toKotlinBackoffStrategy()

    /**
     * Returns a [BackoffStrategy] that applies a linearly increasing delay every retry, where
     * `base` specifies the first delay, and `cap` specifies the maximum delay.
     */
    fun linear(delay: Duration, cap: Duration) =
      RetryInterceptor.BackoffStrategy.linear(delay.toJavaDuration(), cap.toJavaDuration()).toKotlinBackoffStrategy()

    /**
     * Returns a [BackoffStrategy] that applies an exponentially (base 2) increasing delay
     * every retry, where `base` specifies the first delay, and `cap` specifies the
     * maximum delay.
     */
    fun exponential(base: Duration, cap: Duration) =
      RetryInterceptor.BackoffStrategy.exponential(base.toJavaDuration(), cap.toJavaDuration())
        .toKotlinBackoffStrategy()

    /**
     * Returns a [BackoffStrategy] that gets the delay from the value of response's `Retry-After`
     * header, or defers to the given [BackoffStrategy] if no such header exists.
     */
    fun retryAfterOr(fallback: BackoffStrategy) =
      RetryInterceptor.BackoffStrategy.retryAfterOr(fallback.toMethanolBackoffStrategy())
        .toKotlinBackoffStrategy()
  }
}

private fun BackoffStrategy.toMethanolBackoffStrategy() = RetryInterceptor.BackoffStrategy {
  backoff(it).toJavaDuration()
}

private fun RetryInterceptor.BackoffStrategy.toKotlinBackoffStrategy() = BackoffStrategy {
  backoff(it).toKotlinDuration()
}

/**
 * A [spec][Spec] for configuring a listener of retry events.
 *
 * See [RetryInterceptor.Listener].
 */
@Spec
interface RetryListenerSpec {
  fun onFirstAttempt(callback: (Request) -> Unit)

  fun onRetry(callback: (RetryContext<*>, nextRequest: Request, delay: Duration) -> Unit)

  fun onTimeout(callback: (RetryContext<*>) -> Unit)

  fun onExhaustion(callback: (RetryContext<*>) -> Unit)

  fun onComplete(callback: (RetryContext<*>) -> Unit)
}

private class RetryListenerFactorySpec : RetryListenerSpec, FactorySpec<RetryListener> {
  private var onFirstAttempt: (Request) -> Unit = {}
  private var onRetry: (RetryContext<*>, Request, Duration) -> Unit = { _, _, _ -> }
  private var onTimeout: (RetryContext<*>) -> Unit = {}
  private var onExhaustion: (RetryContext<*>) -> Unit = {}
  private var onComplete: (RetryContext<*>) -> Unit = {}

  override fun onFirstAttempt(callback: (Request) -> Unit) {
    onFirstAttempt = callback
  }

  override fun onRetry(callback: (RetryContext<*>, Request, Duration) -> Unit) {
    onRetry = callback
  }

  override fun onTimeout(callback: (RetryContext<*>) -> Unit) {
    onTimeout = callback
  }

  override fun onExhaustion(callback: (RetryContext<*>) -> Unit) {
    onExhaustion = callback
  }

  override fun onComplete(callback: (RetryContext<*>) -> Unit) {
    onComplete = callback
  }

  override fun make(): RetryListener {
    return object : RetryListener {
      val onFirstAttemptCallback = this@RetryListenerFactorySpec.onFirstAttempt
      val onRetryCallback = this@RetryListenerFactorySpec.onRetry
      val onTimeoutCallback = this@RetryListenerFactorySpec.onTimeout
      val onExhaustionCallback = this@RetryListenerFactorySpec.onExhaustion
      val onCompleteCallback = this@RetryListenerFactorySpec.onComplete

      override fun onFirstAttempt(request: HttpRequest) = onFirstAttemptCallback(request)

      override fun onRetry(
        context: RetryInterceptor.Context<*>, nextRequest: HttpRequest, delay: java.time.Duration
      ) = onRetryCallback(context, nextRequest, delay.toKotlinDuration())

      override fun onTimeout(context: RetryContext<*>) = onTimeoutCallback(context)

      override fun onExhaustion(context: RetryContext<*>) = onExhaustionCallback(context)

      override fun onComplete(context: RetryContext<*>) = onCompleteCallback(context)
    }
  }
}

/**
 * A [spec][Spec] for configuring an [Interceptor] that retries requests based on the specified retry conditions.
 *
 * See [RetryInterceptor.Builder].
 */
@Spec
interface RetryInterceptorSpec {
  fun beginWith(requestModifier: (Request) -> Request)

  fun maxRetries(maxRetries: Int)

  fun backoff(backoffStrategy: BackoffStrategy)

  fun onException(
    exceptionTypes: Set<KClass<out Throwable>>,
    requestModifier: RetryContext<*>.() -> Request = { request() }
  )

  fun onException(exceptionPredicate: (Throwable) -> Boolean)

  fun onException(
    exceptionPredicate: (Throwable) -> Boolean,
    requestModifier: RetryContext<*>.() -> Request = { request() }
  )

  fun onException(vararg exceptionTypes: KClass<out Throwable>)

  fun onStatus(vararg codes: Int)

  fun onStatus(codes: Set<Int>, requestModifier: RetryContext<*>.() -> Request = { request() })

  fun onStatus(statusPredicate: (Int) -> Boolean, requestModifier: RetryContext<*>.() -> Request = { request() })

  fun onResponse(
    responsePredicate: (Response<*>) -> Boolean,
    requestModifier: RetryContext<*>.() -> Request = { request() }
  )

  fun on(
    predicate: (RetryContext<*>) -> Boolean,
    requestModifier: RetryContext<*>.() -> Request = { request() }
  )

  fun timeout(timeout: Duration)

  fun listener(block: RetryListenerSpec.() -> Unit) = listener(RetryListenerFactorySpec().apply(block).make())

  fun listener(listener: RetryListener)

  fun throwOnExhaustion()
}

private class RetryInterceptorFactorySpec : RetryInterceptorSpec, FactorySpec<Interceptor> {
  private val builder = RetryInterceptor.newBuilder()

  override fun beginWith(requestModifier: (Request) -> Request) {
    builder.beginWith(requestModifier)
  }

  override fun maxRetries(maxRetries: Int) {
    builder.maxRetries(maxRetries)
  }

  override fun backoff(backoffStrategy: BackoffStrategy) {
    builder.backoff(backoffStrategy.toMethanolBackoffStrategy())
  }

  override fun onException(
    exceptionTypes: Set<KClass<out Throwable>>,
    requestModifier: RetryContext<*>.() -> Request
  ) {
    builder.onException(exceptionTypes.map { it.java }.toSet(), requestModifier)
  }

  override fun onException(exceptionPredicate: (Throwable) -> Boolean) {
    builder.onException(exceptionPredicate)
  }

  override fun onException(
    exceptionPredicate: (Throwable) -> Boolean, requestModifier: RetryContext<*>.() -> Request
  ) {
    builder.onException(exceptionPredicate, requestModifier)
  }

  override fun onException(vararg exceptionTypes: KClass<out Throwable>) {
    builder.onException(*exceptionTypes.map { it.java }.toTypedArray())
  }

  override fun onStatus(vararg codes: Int) {
    builder.onStatus(*codes)
  }

  override fun onStatus(
    codes: Set<Int>,
    requestModifier: RetryContext<*>.() -> Request
  ) {
    builder.onStatus(codes.toSet(), requestModifier)
  }

  override fun onStatus(statusPredicate: (Int) -> Boolean, requestModifier: RetryContext<*>.() -> Request) {
    builder.onStatus(statusPredicate, requestModifier)
  }

  override fun onResponse(responsePredicate: (Response<*>) -> Boolean, requestModifier: RetryContext<*>.() -> Request) {
    builder.onResponse(responsePredicate, requestModifier)
  }

  override fun on(
    predicate: (RetryContext<*>) -> Boolean, requestModifier: RetryContext<*>.() -> Request
  ) {
    builder.on(predicate, requestModifier)
  }

  override fun timeout(timeout: Duration) {
    builder.timeout(timeout.toJavaDuration())
  }

  override fun listener(listener: RetryListener) {
    builder.listener(listener)
  }

  override fun throwOnExhaustion() {
    builder.throwOnExhaustion()
  }

  override fun make(): Interceptor = builder.build().toCoroutineInterceptor()
}

/**
 * Retries when an exception of type [T] is thrown.
 * Equivalent to `onException(setOf(T::class), requestModifier)`.
 */
inline fun <reified T : Throwable> RetryInterceptorSpec.onException(
  noinline requestModifier: RetryContext<*>.() -> Request = { request() }
) = onException(setOf(T::class), requestModifier)

/** Creates a [RetryListener] that listens to retry events as configured by the given block. */
@Suppress("FunctionName")
fun RetryListener(block: RetryListenerSpec.() -> Unit) = RetryListenerFactorySpec().apply(block).make()

/** Creates an [Interceptor] that retries requests as configured by the given block. */
@Suppress("FunctionName")
fun RetryInterceptor(block: RetryInterceptorSpec.() -> Unit) = RetryInterceptorFactorySpec().apply(block).make()
