package com.github.mizosoft.methanol.kotlin

import com.github.mizosoft.methanol.HttpStatus
import com.github.mizosoft.methanol.ResponsePayload
import com.github.mizosoft.methanol.TypeRef
import kotlinx.coroutines.future.await
import java.net.http.HttpResponse

/** Converts this response payload into [T]. */
suspend inline fun <reified T> ResponsePayload.to(): T {
  return toAsync(object : TypeRef<T>() {}).await()
}

/** Converts this payload to [T] using the given body handler. */
suspend fun <T> ResponsePayload.with(bodyHandler: BodyHandler<T>): T {
  return handleWithAsync(bodyHandler).await()
}

/** Returns whether this response is 1xx informational. */
fun Response<*>.isInformational() = HttpStatus.isInformational(this)

/** Returns whether this response is 2xx successful. */
fun Response<*>.isSuccessful() = HttpStatus.isSuccessful(this)

/** Returns whether this response is 3xx redirection. */
fun Response<*>.isRedirection() = HttpStatus.isRedirection(this)

/** Returns whether this response is 4xx client error. */
fun Response<*>.isClientError() = HttpStatus.isClientError(this)

/** Returns whether this response is 5xx server error. */
fun Response<*>.isServerError() = HttpStatus.isServerError(this)

typealias Response<T> = HttpResponse<T>

typealias BodySubscriber<T> = HttpResponse.BodySubscriber<T>

typealias BodySubscribers = HttpResponse.BodySubscribers

typealias BodyHandler<T> = HttpResponse.BodyHandler<T>

typealias PushPromiseHandler<T> = HttpResponse.PushPromiseHandler<T>

typealias BodyHandlers = HttpResponse.BodyHandlers

typealias ResponseInfo = HttpResponse.ResponseInfo
