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

import com.github.mizosoft.methanol.HttpCache
import com.github.mizosoft.methanol.StorageExtension
import java.nio.file.Path
import java.util.concurrent.Executor

/** A [spec][Spec] for configuring a [Cache][com.github.mizosoft.methanol.kotlin.Cache]. */
@Spec
interface CacheSpec {
  fun onDisk(path: Path, maxSizeBytes: Long)

  fun inMemory(maxSizeBytes: Long)

  fun on(storageExtension: StorageExtension)

  fun executor(executor: Executor)
}

private class CacheFactorySpec(private val builder: HttpCache.Builder = Cache.newBuilder()) :
  CacheSpec, FactorySpec<Cache> {
  override fun onDisk(path: Path, maxSizeBytes: Long) {
    builder.cacheOnDisk(path, maxSizeBytes)
  }

  override fun inMemory(maxSizeBytes: Long) {
    builder.cacheOnMemory(maxSizeBytes)
  }

  override fun on(storageExtension: StorageExtension) {
    builder.cacheOn(storageExtension)
  }

  override fun executor(executor: Executor) {
    builder.executor(executor)
  }

  override fun make(): Cache = builder.build()
}

/** A [spec][Spec] for configuring a [CacheChain][com.github.mizosoft.methanol.kotlin.CacheChain]. */
@Spec
interface CacheChainSpec {
  operator fun Cache.unaryPlus()
}

private class CacheChainFactorySpec(private val caches: MutableList<Cache> = ArrayList()) :
  CacheChainSpec, FactorySpec<CacheChain> {
  override fun Cache.unaryPlus() {
    caches += this
  }

  override fun make(): CacheChain = caches
}

typealias Cache = HttpCache

/** A series of caches invoked sequentially during an HTTP call. */
typealias CacheChain = List<Cache>

/** Creates a new [com.github.mizosoft.methanol.kotlin.Cache] as configured by the given spec block. */
@Suppress("FunctionName")
fun Cache(block: CacheSpec.() -> Unit) = CacheFactorySpec().apply(block).make()

/** Creates a new [com.github.mizosoft.methanol.kotlin.CacheChain] as configured by the given spec block. */
@Suppress("FunctionName")
fun CacheChain(block: CacheChainSpec.() -> Unit) = CacheChainFactorySpec().apply(block).make()
