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
