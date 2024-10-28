package com.github.mizosoft.methanol.kotlin

import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.kotlin.KotlinAdapter.Companion.Decoder
import com.github.mizosoft.methanol.kotlin.KotlinAdapter.Companion.Encoder
import com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.listSerialDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.serializersModuleOf
import kotlinx.serialization.serializer
import java.nio.charset.StandardCharsets.UTF_16
import kotlin.test.Test

@Serializable(CompactPointSerializer::class)
data class CompactPoint(val x: Int, val y: Int)

object CompactPointSerializer : KSerializer<CompactPoint> {
  override val descriptor: SerialDescriptor =
    @OptIn(ExperimentalSerializationApi::class) listSerialDescriptor<Int>()

  override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, point: CompactPoint) {
    encoder.encodeSerializableValue(serializer<List<Int>>(), listOf(point.x, point.y))
  }

  override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): CompactPoint {
    val list = decoder.decodeSerializableValue(serializer<List<Int>>())
    return CompactPoint(list[0], list[1])
  }
}

class KotlinAdapterJsonTest {
  @Test
  fun encode() {
    verifyThat(Encoder(Json, MediaType.APPLICATION_JSON))
      .converting(Point(1, 2)).succeedsWith("{\"x\":1,\"y\":2}")
  }

  @Test
  fun decode() {
    verifyThat(Decoder(Json, MediaType.APPLICATION_JSON))
      .converting<Point>()
      .withBody("{\"x\":1, \"y\":2}")
      .succeedsWith(Point(1, 2))
  }

  @Test
  fun encodeWithUtf16() {
    verifyThat(Encoder(Json, MediaType.APPLICATION_JSON))
      .converting(Point(1, 2))
      .withMediaType("application/json; charset=utf-16")
      .succeedsWith("{\"x\":1,\"y\":2}", UTF_16)
  }

  @Test
  fun decodeWithUtf16() {
    verifyThat(Decoder(Json, MediaType.APPLICATION_JSON))
      .converting<Point>()
      .withMediaType("application/json; charset=utf-16")
      .withBody("{\"x\":1, \"y\":2}", UTF_16)
      .succeedsWith(
        Point(1, 2)
      )
  }

  @Test
  fun encodeList() {
    verifyThat(Encoder(Json, MediaType.APPLICATION_JSON))
      .converting(listOf(Point(1, 2), Point(3, 4)), TypeRef<List<Point>>())
      .succeedsWith("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]")
  }

  @Test
  fun decodeList() {
    verifyThat(Decoder(Json, MediaType.APPLICATION_JSON))
      .converting<List<Point>>()
      .withBody("[{\"x\":1, \"y\":2}, {\"x\":3, \"y\":4}]")
      .succeedsWith(listOf(Point(1, 2), Point(3, 4)))
  }

  @Test
  fun encodeWithCustomSerializer() {
    verifyThat(
      Encoder(
        Json { serializersModule = serializersModuleOf(CompactPointSerializer) },
        MediaType.APPLICATION_JSON
      )
    ).converting(CompactPoint(1, 2))
      .withMediaType(MediaType.APPLICATION_JSON)
      .succeedsWith("[1,2]")
  }

  @Test
  fun decodeWithCustomDeserializer() {
    verifyThat(
      Decoder(
        Json { serializersModule = serializersModuleOf(CompactPointSerializer) },
        MediaType.APPLICATION_JSON
      )
    ).converting<CompactPoint>()
      .withMediaType(MediaType.APPLICATION_JSON)
      .withBody("[1, 2]")
      .succeedsWith(CompactPoint(1, 2))
  }
}
