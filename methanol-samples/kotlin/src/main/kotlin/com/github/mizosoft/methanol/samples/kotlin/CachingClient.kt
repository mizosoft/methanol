/*
 * Copyright (c) 2024 Moataz Hussein
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol.samples.kotlin

import com.github.mizosoft.methanol.CacheAwareResponse
import com.github.mizosoft.methanol.kotlin.BodyHandlers
import com.github.mizosoft.methanol.kotlin.Client
import com.github.mizosoft.methanol.kotlin.close
import com.github.mizosoft.methanol.kotlin.get
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

object CachingClient {
  val client = Client {
    userAgent("Chuck Norris")
    cache {
      onDisk(Path.of(".cache"), 500 * 1024 * 1024) // Occupy at most 500Mb on disk.
    }
  }

  suspend fun run() {
    val response =
      client.get("https://i.imgur.com/V79ulbT.gif", BodyHandlers.ofFile(Path.of("images/popcat.gif"))) {
        cacheControl {
          maxAge(5.seconds) // Override server's max-age.
        }
      } as CacheAwareResponse<Path>
    println(
      "$response - ${response.cacheStatus()} (Cached for ${response.headers()["Age"].firstOrNull() ?: -1} seconds)"
    )
  }

  fun closeCache() {
    client.caches().close()
  }
}
