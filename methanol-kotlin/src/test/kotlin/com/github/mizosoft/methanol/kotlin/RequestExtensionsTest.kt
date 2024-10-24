package com.github.mizosoft.methanol.kotlin

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNull
import assertk.assertions.isSameInstanceAs
import assertk.assertions.prop
import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.MimeBodyPublisher
import org.junit.jupiter.api.Test
import java.net.URI

class RequestExtensionsTest {
  @Test
  fun toTaggableRequest() {
    val tag = Any()
    val request = Request {
      tags {
        +tag
      }
    } as Request
    assertThat(request.toTaggableRequest().tagOf<Any>()).isSameInstanceAs(tag)
    assertThat(
      Request.newBuilder(URI.create("https://example.com")).build().toTaggableRequest().tagOf<Any>()
    ).isNull()
  }

  @Test
  fun withMediaType() {
    assertThat(BodyPublishers.ofString("Hello").withMediaType(MediaType.TEXT_PLAIN))
      .isInstanceOf(MimeBodyPublisher::class)
      .prop(MimeBodyPublisher::mediaType)
      .isEqualTo(MediaType.TEXT_PLAIN)
  }
}
