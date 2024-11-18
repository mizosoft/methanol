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
