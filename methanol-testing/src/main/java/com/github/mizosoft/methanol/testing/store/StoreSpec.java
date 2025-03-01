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

import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.MockDelayer;
import com.github.mizosoft.methanol.testing.store.StoreConfig.Execution;
import com.github.mizosoft.methanol.testing.store.StoreConfig.FileSystemType;
import com.github.mizosoft.methanol.testing.store.StoreConfig.StoreType;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Specifies one or more {@code Store} configurations for a test case. */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)
public @interface StoreSpec {
  StoreType[] tested() default {
    StoreType.MEMORY, StoreType.DISK, StoreType.REDIS_STANDALONE, StoreType.REDIS_CLUSTER
  };

  /** Specifies {@code StoreTypes} to skip in testing. */
  StoreType[] skipped() default {};

  long maxSize() default Long.MAX_VALUE;

  FileSystemType[] fileSystem() default {
    FileSystemType.IN_MEMORY,
    FileSystemType.SYSTEM,
    FileSystemType.EMULATED_WINDOWS,
    FileSystemType.NONE
  };

  Execution execution() default Execution.ASYNC;

  int appVersion() default 1;

  /** Delay between automatic index updates done by the disk store. */
  int indexUpdateDelaySeconds() default StoreConfig.UNSET_NUMBER;

  /** Whether {@link MockClock} should automatically advance itself by 1 second. */
  boolean autoAdvanceClock() default true;

  /**
   * Whether {@link MockDelayer} should eagerly dispatch ready tasks (tasks whose delay is
   * evaluated) whenever a task is submitted.
   */
  boolean dispatchEagerly() default true;

  /** The number of seconds an inactive editor lock gets to live. */
  int editorLockInactiveTtlSeconds() default StoreConfig.UNSET_NUMBER;

  /** The number of seconds an inactive stale entry gets to live. */
  int staleEntryInactiveTtlSeconds() default StoreConfig.UNSET_NUMBER;
}
