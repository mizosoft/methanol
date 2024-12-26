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
suspend fun <T> ResponsePayload.getWith(bodyHandler: BodyHandler<T>): T {
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
