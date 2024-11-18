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

import com.github.mizosoft.methanol.AdapterCodec
import com.github.mizosoft.methanol.BodyAdapter.Decoder
import com.github.mizosoft.methanol.BodyAdapter.Encoder
import com.github.mizosoft.methanol.BodyAdapter.Hints
import com.github.mizosoft.methanol.TypeRef
import com.github.mizosoft.methanol.adapter.ForwardingDecoder

/** A [spec][Spec] for configuring an [AdapterCodec]. */
@Spec
interface AdapterCodecSpec {
  operator fun Encoder.unaryPlus()

  operator fun Decoder.unaryPlus()

  fun basicEncoder()

  fun basicDecoder()

  fun basic() {
    basicEncoder()
    basicDecoder()
  }
}

private class AdapterCodecFactorySpec(
  private val builder: AdapterCodec.Builder = AdapterCodec.newBuilder()
) : AdapterCodecSpec, FactorySpec<AdapterCodec> {
  override operator fun Encoder.unaryPlus() {
    builder.encoder(this)
  }

  override operator fun Decoder.unaryPlus() {
    builder.decoder(this)
  }

  override fun basicEncoder() {
    builder.basicEncoder()
  }

  override fun basicDecoder() {
    // Extend the basic decoder to handle Kotlin's Unit.
    builder.decoder(object : ForwardingDecoder(Decoder.basic()) {
      override fun supportsType(type: TypeRef<*>) =
        super.supportsType(type) || (type.isRawType && type.rawType() == Unit.javaClass)

      override fun <T : Any> toObject(typeRef: TypeRef<T>, hints: Hints): BodySubscriber<T> {
        return when {
          typeRef.isRawType && typeRef.rawType() == Unit.javaClass -> {
            require(isCompatibleWith(hints.mediaTypeOrAny())) { "Adapter not compatible with ${hints.mediaTypeOrAny()}" }
            @Suppress("UNCHECKED_CAST")
            BodySubscribers.replacing(Unit) as BodySubscriber<T>
          }

          else -> super.toObject(typeRef, hints)
        }
      }
    })
  }

  override fun make(): AdapterCodec = builder.build()
}

/**
 * Returns a [BodyHandler] for the given type.
 *
 * @throws UnsupportedOperationException if no decoder that supports the given type is found
 */
inline fun <reified T> AdapterCodec.handlerOf(hints: Hints = Hints.empty()): BodyHandler<T> =
  handlerOf(TypeRef<T>(), hints)

/**
 *  Returns a [BodySubscriber] for the given type and [Hints].
 *
 * @throws UnsupportedOperationException if no decoder that supports the given type or the given hints' media type is found
 */
inline fun <reified T> AdapterCodec.subscriberOf(hints: Hints = Hints.empty()): BodySubscriber<T> =
  subscriberOf(TypeRef<T>(), hints)

/** Creates a new [com.github.mizosoft.methanol.AdapterCodec] as configured by the given spec block. */
fun AdapterCodec(block: AdapterCodecSpec.() -> Unit) = AdapterCodecFactorySpec().apply(block).make()
