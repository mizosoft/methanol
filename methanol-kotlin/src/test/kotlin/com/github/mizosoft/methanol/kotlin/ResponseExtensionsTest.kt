package com.github.mizosoft.methanol.kotlin

import assertk.assertThat
import assertk.assertions.isTrue
import com.github.mizosoft.methanol.ResponseBuilder
import java.net.URI
import kotlin.test.Test

class ResponseExtensionsTest {
  private val responseBuilder =
    ResponseBuilder<Unit>()
      .uri(URI.create("https://example.com"))
      .request(Request {
        uri("https://example.com")
      })
      .version(HttpVersion.HTTP_1_1)


  @Test
  fun informationalResponseIsInformational() {
    assertThat(responseBuilder.statusCode(100).build().isInformational()).isTrue()
  }

  @Test
  fun successfulResponseIsSuccessful() {
    assertThat(responseBuilder.statusCode(200).build().isSuccessful()).isTrue()
  }

  @Test
  fun redirectionResponseIsRedirection() {
    assertThat(responseBuilder.statusCode(300).build().isRedirection()).isTrue()
  }

  @Test
  fun clientErrorResponseIsClientError() {
    assertThat(responseBuilder.statusCode(400).build().isClientError()).isTrue()
  }

  @Test
  fun serverErrorResponseIsServerError() {
    assertThat(responseBuilder.statusCode(500).build().isServerError()).isTrue()
  }
}
