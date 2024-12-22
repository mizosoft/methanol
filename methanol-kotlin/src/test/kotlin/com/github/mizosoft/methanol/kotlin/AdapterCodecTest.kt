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

import assertk.all
import assertk.assertThat
import assertk.assertions.hasSize
import assertk.assertions.prop
import com.github.mizosoft.methanol.AdapterCodec
import com.github.mizosoft.methanol.BodyAdapter.Decoder
import com.github.mizosoft.methanol.BodyAdapter.Encoder
import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.TypeRef
import kotlin.test.Test

class AdapterCodecTest {
  @Test
  fun createAdapterCodec() {
    val adapterCodec = AdapterCodec {
      +object : Encoder {
        override fun isCompatibleWith(mediaType: MediaType) = TODO("Not yet implemented")
        override fun supportsType(type: TypeRef<*>) = TODO("Not yet implemented")
        override fun toBody(`object`: Any, mediaType: MediaType?) = TODO("Not yet implemented")
      }
      +object : Decoder {
        override fun isCompatibleWith(mediaType: MediaType) = TODO("Not yet implemented")
        override fun supportsType(type: TypeRef<*>?) = TODO("Not yet implemented")
        override fun <T : Any?> toObject(
          objectType: TypeRef<T>,
          mediaType: MediaType?
        ) = TODO("Not yet implemented")
      }
      basic()
    }
    assertThat(adapterCodec).all {
      prop(AdapterCodec::encoders).hasSize(2)
      prop(AdapterCodec::decoders).hasSize(2)
    }
  }
}