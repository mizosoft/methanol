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
