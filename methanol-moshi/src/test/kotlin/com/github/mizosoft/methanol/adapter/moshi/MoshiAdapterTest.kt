package com.github.mizosoft.methanol.adapter.moshi

import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.adapters.moshi.MoshiAdapter.Companion.Decoder
import com.github.mizosoft.methanol.adapters.moshi.MoshiAdapter.Companion.Encoder
import com.github.mizosoft.methanol.kotlin.TypeRef
import com.github.mizosoft.methanol.testing.verifiers.DecoderVerifier
import com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat
import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.ToJson
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.jupiter.api.Test

object CompactPointAdapter {
  @ToJson
  @Suppress("unused")
  fun toJson(writer: JsonWriter, point: Point) {
    writer.beginArray()
    writer.value(point.x)
    writer.value(point.y)
    writer.endArray()
  }

  @FromJson
  @Suppress("unused")
  fun fromJson(reader: JsonReader): Point {
    reader.beginArray()
    return Point(reader.nextInt(), reader.nextInt()).also {
      reader.endArray()
    }
  }
}

inline fun <reified T> DecoderVerifier.converting() = converting(TypeRef<T>())

data class Point(val x: Int, val y: Int)

class MoshiAdapterJsonTest {
  @Test
  fun encode() {
    verifyThat(
      Encoder(
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
        MediaType.APPLICATION_JSON
      )
    ).converting(Point(1, 2)).succeedsWith("{\"x\":1,\"y\":2}")
  }

  @Test
  fun decode() {
    verifyThat(
      Decoder(
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
        MediaType.APPLICATION_JSON
      )
    ).converting<Point>()
      .withBody("{\"x\":1, \"y\":2}")
      .succeedsWith(Point(1, 2))
  }

  @Test
  fun encodeList() {
    verifyThat(
      Encoder(
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build(),
        MediaType.APPLICATION_JSON
      )
    ).converting(listOf(Point(1, 2), Point(3, 4)), TypeRef<List<Point>>())
      .succeedsWith("[{\"x\":1,\"y\":2},{\"x\":3,\"y\":4}]")
  }

  @Test
  fun decodeList() {
    verifyThat(Decoder(Moshi.Builder().add(KotlinJsonAdapterFactory()).build()))
      .converting<List<Point>>()
      .withBody("[{\"x\":1, \"y\":2}, {\"x\":3, \"y\":4}]")
      .succeedsWith(listOf(Point(1, 2), Point(3, 4)))
  }

  @Test
  fun encodeWithCustomSerializer() {
    verifyThat(Encoder(Moshi.Builder().add(CompactPointAdapter).build()))
      .converting(Point(1, 2))
      .withMediaType(MediaType.APPLICATION_JSON)
      .succeedsWith("[1,2]")
  }

  @Test
  fun decodeWithCustomDeserializer() {
    verifyThat(Decoder(Moshi.Builder().add(CompactPointAdapter).build()))
      .converting<Point>()
      .withMediaType(MediaType.APPLICATION_JSON)
      .withBody("[1, 2]")
      .succeedsWith(Point(1, 2))
  }

  @Test
  fun decodeDeferred() {
    verifyThat(Decoder(Moshi.Builder().add(CompactPointAdapter).build()))
      .converting<Point>()
      .withDeferredBody("[1, 2]")
      .succeedsWith(Point(1, 2))
  }
}
