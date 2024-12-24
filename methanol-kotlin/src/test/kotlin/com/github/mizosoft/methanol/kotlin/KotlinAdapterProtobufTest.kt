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

import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.kotlin.KotlinAdapter.Companion.Decoder
import com.github.mizosoft.methanol.kotlin.KotlinAdapter.Companion.Encoder
import com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import java.nio.ByteBuffer
import kotlin.test.Test

@OptIn(ExperimentalSerializationApi::class)
class KotlinAdapterProtobufTest {
  @Test
  fun encode() {
    verifyThat(Encoder(ProtoBuf, MediaType.APPLICATION_JSON))
      .converting(Point(1, 2))
      .succeedsWith(ByteBuffer.wrap(ProtoBuf.encodeToByteArray(Point.serializer(), Point(1, 2))))
  }

  @Test
  fun decode() {
    verifyThat(Decoder(ProtoBuf, MediaType.APPLICATION_JSON))
      .converting<Point>()
      .withBody(ByteBuffer.wrap(ProtoBuf.encodeToByteArray(Point.serializer(), Point(1, 2))))
      .succeedsWith(Point(1, 2))
  }
}
