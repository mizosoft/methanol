package com.github.mizosoft.methanol.samples.kotlin

import com.github.mizosoft.methanol.CacheAwareResponse
import com.github.mizosoft.methanol.kotlin.BodyHandlers
import com.github.mizosoft.methanol.kotlin.Client
import com.github.mizosoft.methanol.kotlin.get
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

object Caching {
  val client = Client {
    userAgent("Chuck Norris")
    cache {
      onDisk(Path.of(".cache"), 500 * 1024 * 1024) // Occupy at most 500Mb on disk.
    }
  }

  suspend fun run() {
    // https://i.imgur.com/V79ulbT.gif
    val response =
      client.get("https://i.imgur.com/V79ulbT.gif", BodyHandlers.ofFile(Path.of("popcat.gif"))) {
        cacheControl {
          // Override server's max-age.
          minFresh(2.seconds)
        }
      } as CacheAwareResponse<Path>
    println(
      "$response - ${response.cacheStatus()} (Cached for ${response.headers()["Age"].firstOrNull() ?: -1} seconds)"
    )
  }
}
