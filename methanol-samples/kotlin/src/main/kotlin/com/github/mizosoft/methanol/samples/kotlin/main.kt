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

package com.github.mizosoft.methanol.samples.kotlin

suspend fun main() {
  System.setProperty("imgur.client.id", "187e3cdbaa11b1a")
  listOf<Example<*>>(
    Example<CachingClient>(CachingClient) { run() },
    Example<Coroutines>(Coroutines) { run() },
    Example<GetPostJson>(GetPostJson) { runGet() },
    Example<GetPostJson>(GetPostJson) { runPost() },
    Example<GetPostString>(GetPostString) { runGet() },
    Example<GetPostString>(GetPostString) { runPost() },
    Example<Interceptors>(Interceptors) { run() },
    Example<MultipartAndFormUploads>(MultipartAndFormUploads) { multipartUpload() },
    Example<MultipartAndFormUploads>(MultipartAndFormUploads) { formUpload() },
  ).forEach { it.run() }
  CachingClient.closeCache()
}

data class Example<T>(val receiver: T, val fn: suspend T.() -> Unit) {
  suspend fun run() {
    receiver.fn()
  }
}
