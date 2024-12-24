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

package com.github.mizosoft.methanol.adapters.moshi

import com.github.mizosoft.methanol.BodyAdapter
import com.github.mizosoft.methanol.BodyAdapter.Hints
import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.TypeRef
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter
import com.squareup.moshi.Moshi
import okio.buffer
import okio.source
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodySubscriber
import java.net.http.HttpResponse.BodySubscribers
import java.util.function.Supplier

abstract class MoshiAdapter(internal val moshi: Moshi, vararg mediaTypes: MediaType) :
  AbstractBodyAdapter(*mediaTypes) {
  override fun supportsType(typeRef: TypeRef<*>): Boolean {
    try {
      moshi.adapter<Object>(typeRef.type())
      return true
    } catch (_: IllegalArgumentException) {
      return false
    }
  }

  companion object {
    fun Encoder(moshi: Moshi, vararg mediaTypes: MediaType): BodyAdapter.Encoder =
      MoshiAdapter.Encoder(moshi, *mediaTypes)

    fun Encoder(moshi: Moshi): BodyAdapter.Encoder =
      MoshiAdapter.Encoder(moshi, MediaType.APPLICATION_JSON)

    fun Decoder(moshi: Moshi, vararg mediaTypes: MediaType): BodyAdapter.Decoder =
      MoshiAdapter.Decoder(moshi, *mediaTypes)

    fun Decoder(moshi: Moshi): BodyAdapter.Decoder =
      MoshiAdapter.Decoder(moshi, MediaType.APPLICATION_JSON)
  }

  private class Encoder(moshi: Moshi, vararg mediaTypes: MediaType) :
    MoshiAdapter(moshi, *mediaTypes), BaseEncoder {
    override fun <T> toBody(value: T, typeRef: TypeRef<T>, hints: Hints): BodyPublisher {
      requireSupport(typeRef, hints)
      return BodyPublishers.ofString(
        moshi.adapter<T>(typeRef.type()).toJson(value),
        hints.mediaTypeOrAny().charsetOrUtf8()
      )
    }
  }

  private class Decoder(moshi: Moshi, vararg mediaTypes: MediaType) :
    MoshiAdapter(moshi, *mediaTypes), BaseDecoder {
    override fun <T> toObject(typeRef: TypeRef<T>, hints: Hints): BodySubscriber<T> {
      requireSupport(typeRef, hints)
      val adapter = moshi.adapter<T>(typeRef.type())
      return BodySubscribers.mapping(
        BodySubscribers.ofString(
          hints.mediaTypeOrAny().charsetOrUtf8()
        )
      ) { json -> adapter.fromJson(json)!! }
    }

    override fun <T> toDeferredObject(
      typeRef: TypeRef<T>,
      hints: Hints
    ): BodySubscriber<Supplier<T>> {
      requireSupport(typeRef, hints)
      val adapter = moshi.adapter<T>(typeRef.type())
      return BodySubscribers.mapping(
        BodySubscribers.ofInputStream()
      ) { input -> Supplier { adapter.fromJson(input.source().buffer())!! } }
    }
  }
}
