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

package com.github.mizosoft.methanol.testing.store;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;

abstract class RedisStoreConfig extends StoreConfig {
  private final int editorLockInactiveTtlSeconds;
  private final int staleEntryInactiveTtlSeconds;

  RedisStoreConfig(
      RedisStoreType redisStoreType,
      int appVersion,
      int editorLockInactiveTtlSeconds,
      int staleEntryInactiveTtlSeconds) {
    super(redisStoreType.storeType, Long.MAX_VALUE, appVersion);
    this.editorLockInactiveTtlSeconds =
        editorLockInactiveTtlSeconds != UNSET_NUMBER
            ? editorLockInactiveTtlSeconds
            : Integer.MAX_VALUE;
    this.staleEntryInactiveTtlSeconds =
        staleEntryInactiveTtlSeconds != UNSET_NUMBER
            ? staleEntryInactiveTtlSeconds
            : Integer.MAX_VALUE;
    requireArgument(
        this.editorLockInactiveTtlSeconds > 0,
        "Non-positive editorLockInactiveTtlSeconds: %d",
        this.editorLockInactiveTtlSeconds);
    requireArgument(
        this.staleEntryInactiveTtlSeconds > 0,
        "Non-positive staleEntryInactiveTtlSeconds: %d",
        this.staleEntryInactiveTtlSeconds);
  }

  int editorLockInactiveTtlSeconds() {
    return editorLockInactiveTtlSeconds;
  }

  int staleEntryInactiveTtlSeconds() {
    return staleEntryInactiveTtlSeconds;
  }

  @Override
  public String toString() {
    return getClass().getSimpleName()
        + "[editorLockInactiveTtlSeconds="
        + editorLockInactiveTtlSeconds
        + ", staleEntryInactiveTtlSeconds="
        + staleEntryInactiveTtlSeconds
        + "]";
  }

  enum RedisStoreType {
    STANDALONE(StoreType.REDIS_STANDALONE),
    CLUSTER(StoreType.REDIS_CLUSTER);

    final StoreType storeType;

    RedisStoreType(StoreType storeType) {
      this.storeType = storeType;
    }
  }
}
