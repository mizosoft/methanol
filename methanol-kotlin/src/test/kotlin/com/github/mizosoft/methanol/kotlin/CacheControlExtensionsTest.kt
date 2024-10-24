package com.github.mizosoft.methanol.kotlin

import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class CacheControlExtensionsTest {
  @Test
  fun toCacheControlFromString() {
    assertThat("max-age=1".toCacheControl()).isEqualTo(CacheControl { maxAge(1.seconds) })
  }

  @Test
  fun toCacheControlFromStringList() {
    assertThat(listOf("max-age=1", "my-directive", "min-fresh=2").toCacheControl()).isEqualTo(
      CacheControl {
        maxAge(1.seconds)
        minFresh(2.seconds)
        +"my-directive"
      })
  }
}
