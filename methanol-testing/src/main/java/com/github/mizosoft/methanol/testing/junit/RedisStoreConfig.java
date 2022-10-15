/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing.junit;

import com.github.mizosoft.methanol.testing.junit.StoreSpec.StoreType;

public final class RedisStoreConfig extends StoreConfig {
  private final long editorLockTimeToLiveMillis;
  private final long staleEntryTimeToLiveMillis;
  private final int appVersion;

  public RedisStoreConfig(
      boolean autoInit,
      long editorLockTimeToLiveMillis,
      long staleEntryTimeToLiveMillis,
      int appVersion) {
    super(StoreType.REDIS, autoInit, Long.MAX_VALUE);
    this.editorLockTimeToLiveMillis = editorLockTimeToLiveMillis;
    this.staleEntryTimeToLiveMillis = staleEntryTimeToLiveMillis;
    this.appVersion = appVersion;
  }

  public long editorLockTimeToLiveMillis() {
    return editorLockTimeToLiveMillis;
  }

  public long staleEntryTimeToLiveMillis() {
    return staleEntryTimeToLiveMillis;
  }

  public int appVersion() {
    return appVersion;
  }
}
