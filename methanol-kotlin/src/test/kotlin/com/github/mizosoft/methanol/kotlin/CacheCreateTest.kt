/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.kotlin

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.github.mizosoft.methanol.internal.cache.InternalStorageExtension
import com.github.mizosoft.methanol.internal.cache.Store
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.Executor
import kotlin.test.Test

class CacheCreateTest {
  @Test
  fun createMemoryCache() {
    val cache = Cache {
      inMemory(1024)
    }
    assertThat(cache.maxSize()).isEqualTo(1024)
  }

  @Test
  fun createDiskCache(@TempDir path: Path) {
    val cache = Cache {
      onDisk(path, 1024 * 1024)
    }
    assertThat(cache.maxSize()).isEqualTo(1024 * 1024)
  }

  @Test
  fun createCacheWithStorageExtension() {
    val cache = Cache {
      on(object : InternalStorageExtension {
        override fun createStore(
          executor: Executor,
          appVersion: Int
        ) = object : Store {
          override fun maxSize() = 69L
          override fun view(
            key: String,
            executor: Executor
          ) = TODO("Not yet implemented")

          override fun edit(
            key: String,
            executor: Executor
          ) = TODO("Not yet implemented")

          override fun iterator() = TODO("Not yet implemented")
          override fun remove(key: String) = TODO("Not yet implemented")
          override fun clear() = TODO("Not yet implemented")
          override fun size() = TODO("Not yet implemented")
          override fun dispose() = TODO("Not yet implemented")
          override fun close() = TODO("Not yet implemented")
          override fun flush() = TODO("Not yet implemented")
        }

        override fun createStoreAsync(
          executor: Executor,
          appVersion: Int
        ) = TODO("Not yet implemented")
      })
    }
    assertThat(cache.maxSize()).isEqualTo(69L)
  }
}