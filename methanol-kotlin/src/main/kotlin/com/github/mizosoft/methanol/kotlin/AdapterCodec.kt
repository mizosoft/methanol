package com.github.mizosoft.methanol.kotlin

import com.github.mizosoft.methanol.AdapterCodec
import com.github.mizosoft.methanol.BodyAdapter.Decoder
import com.github.mizosoft.methanol.BodyAdapter.Encoder
import com.github.mizosoft.methanol.BodyAdapter.Hints
import com.github.mizosoft.methanol.MediaType
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
        type.rawType() == Unit.javaClass || super.supportsType(type)

      override fun <T : Any> toObject(
        objectType: TypeRef<T>,
        mediaType: MediaType?
      ): BodySubscriber<T> {
        return when {
          objectType.rawType() == Unit.javaClass -> {
            require(isCompatibleWith(mediaType)) { "Adapter not compatible with $mediaType" }
            @Suppress("UNCHECKED_CAST")
            BodySubscribers.replacing(Unit) as BodySubscriber<T>
          }

          else -> super.toObject(objectType, mediaType)
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
