/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

import java.util.OptionalInt;

class AbstractRedisStoreConfig extends StoreConfig {
  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private final OptionalInt editorLockTimeToLiveSeconds;

  @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
  private final OptionalInt staleEntryTimeToLiveSeconds;

  AbstractRedisStoreConfig(
      RedisStoreType redisStoreType,
      int appVersion,
      int editorLockTimeToLiveSeconds,
      int staleEntryTimeToLiveSeconds) {
    super(redisStoreType.storeType, Long.MAX_VALUE, appVersion);
    this.editorLockTimeToLiveSeconds =
        editorLockTimeToLiveSeconds != UNSET_NUMBER
            ? OptionalInt.of(editorLockTimeToLiveSeconds)
            : OptionalInt.empty();
    this.editorLockTimeToLiveSeconds.ifPresent(
        value -> requireArgument(value >= 0, "Expected a non-negative value, got: %d", value));
    this.staleEntryTimeToLiveSeconds =
        staleEntryTimeToLiveSeconds != UNSET_NUMBER
            ? OptionalInt.of(staleEntryTimeToLiveSeconds)
            : OptionalInt.empty();
    this.staleEntryTimeToLiveSeconds.ifPresent(
        value -> requireArgument(value >= 0, "Expected a non-negative value, got: %d", value));
  }

  OptionalInt editorLockTtlSeconds() {
    return editorLockTimeToLiveSeconds;
  }

  OptionalInt staleEntryTtlSeconds() {
    return staleEntryTimeToLiveSeconds;
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
