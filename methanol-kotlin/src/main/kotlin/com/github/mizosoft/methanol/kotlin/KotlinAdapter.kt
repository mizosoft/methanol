package com.github.mizosoft.methanol.kotlin

import com.github.mizosoft.methanol.BodyAdapter
import com.github.mizosoft.methanol.BodyAdapter.Hints
import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.TypeRef
import com.github.mizosoft.methanol.adapter.AbstractBodyAdapter
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.serializer
import kotlinx.serialization.serializerOrNull
import java.net.http.HttpRequest.BodyPublisher
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse.BodySubscriber
import java.net.http.HttpResponse.BodySubscribers

abstract class KotlinAdapter(internal val format: SerialFormat, vararg mediaTypes: MediaType) :
  AbstractBodyAdapter(*mediaTypes) {
  init {
    if (!(format is StringFormat || format is BinaryFormat)) {
      throw IllegalArgumentException(
        "SerialFormat must be either a StringFormat or a BinaryFormat: $format"
      )
    }

    if (mediaTypes.isEmpty()) {
      throw IllegalArgumentException("Expected one or more media types")
    }
  }

  override fun supportsType(typeRef: TypeRef<*>) =
    format.serializersModule.serializerOrNull(typeRef.type()) != null

  companion object {
    fun Encoder(format: SerialFormat, vararg mediaTypes: MediaType): BodyAdapter.Encoder =
      KotlinAdapter.Encoder(format, *mediaTypes)

    fun Decoder(format: SerialFormat, vararg mediaTypes: MediaType): BodyAdapter.Decoder =
      KotlinAdapter.Decoder(format, *mediaTypes)
  }

  private class Encoder(format: SerialFormat, vararg mediaTypes: MediaType) :
    KotlinAdapter(format, *mediaTypes), BaseEncoder {
    override fun <T : Any> toBody(value: T, typeRef: TypeRef<T>, hints: Hints): BodyPublisher {
      requireSupport(typeRef, hints)
      return when (format) {
        is StringFormat -> BodyPublishers.ofString(
          format.encodeToString(format.serializersModule.serializer(typeRef.type()), value),
          hints.mediaTypeOrAny().charsetOrUtf8()
        )

        is BinaryFormat -> BodyPublishers.ofByteArray(
          format.encodeToByteArray(format.serializersModule.serializer(typeRef.type()), value)
        )

        else -> throw AssertionError("Unexpected format: $format")
      }
    }
  }

  private class Decoder(format: SerialFormat, vararg mediaTypes: MediaType) :
    KotlinAdapter(format, *mediaTypes), BaseDecoder {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> toObject(typeRef: TypeRef<T>, hints: Hints): BodySubscriber<T> {
      requireSupport(typeRef, hints)
      return when (format) {
        is StringFormat -> BodySubscribers.mapping(
          BodySubscribers.ofString(hints.mediaTypeOrAny().charsetOrUtf8())
        ) { string ->
          format.decodeFromString(
            format.serializersModule.serializer(typeRef.type()) as KSerializer<T>,
            string
          )
        }

        is BinaryFormat -> BodySubscribers.mapping(BodySubscribers.ofByteArray()) { bytes ->
          format.decodeFromByteArray(
            format.serializersModule.serializer(typeRef.type()) as KSerializer<T>,
            bytes
          )
        }

        else -> throw AssertionError("Unexpected format: $format")
      }
    }
  }
}
