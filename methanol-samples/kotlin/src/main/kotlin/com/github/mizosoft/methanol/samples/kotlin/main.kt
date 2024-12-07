package com.github.mizosoft.methanol.samples.kotlin

suspend fun main() {
  System.setProperty(
    "com.github.mizosoft.methanol.internal.cache.CacheWritingPublisher.waitForCommit",
    "true"
  )

  Caching.run()
}
