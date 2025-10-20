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

import com.github.mizosoft.methanol.testing.ByteBufferCollector
import com.github.mizosoft.methanol.testing.TestUtils
import com.github.mizosoft.methanol.testing.verifiers.DecoderVerifier
import kotlinx.serialization.Serializable
import java.net.http.HttpRequest.BodyPublisher
import java.nio.charset.StandardCharsets.UTF_8

fun BodyPublisher.readString() = UTF_8.decode(ByteBufferCollector.collect(this)).toString()

inline fun <reified T> DecoderVerifier.converting() = converting(TypeRef<T>())

@Serializable
data class Point(val x: Int, val y: Int)

fun String.gzipped(): ByteArray = TestUtils.gzip(this)
