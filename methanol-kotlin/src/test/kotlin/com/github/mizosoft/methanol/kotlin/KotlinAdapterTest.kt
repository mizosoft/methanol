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
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.StringFormat
import kotlinx.serialization.modules.SerializersModule
import org.junit.jupiter.api.Test

private object StubFormat : StringFormat {
  override fun <T> decodeFromString(
    deserializer: DeserializationStrategy<T>,
    string: String
  ) = TODO("Not yet implemented")

  override fun <T> encodeToString(
    serializer: SerializationStrategy<T>,
    value: T
  ) = TODO("Not yet implemented")

  override val serializersModule: SerializersModule
    get() = TODO("Not yet implemented")
}

class KotlinAdapterTest {
  @Test
  fun compatibleMediaTypes() {
    verifyThat(Encoder(StubFormat, MediaType.APPLICATION_JSON))
      .isCompatibleWith("application/json")
      .isCompatibleWith("application/json; charset=utf-8")
      .isCompatibleWith("application/*")
      .isCompatibleWith("*/*")
      .isNotCompatibleWith("text/plain")
    verifyThat(Decoder(StubFormat, MediaType.APPLICATION_JSON))
      .isCompatibleWith("application/json")
      .isCompatibleWith("application/json; charset=utf-8")
      .isCompatibleWith("application/*")
      .isCompatibleWith("*/*")
      .isNotCompatibleWith("text/plain")
  }
}
