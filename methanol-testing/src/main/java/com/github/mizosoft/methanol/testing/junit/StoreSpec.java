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

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import com.github.mizosoft.methanol.testing.MockClock;
import com.github.mizosoft.methanol.testing.MockExecutor;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Specifies one or more {@code Store} configurations for a test case. */
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
@Retention(RetentionPolicy.RUNTIME)
public @interface StoreSpec {
  int DEFAULT_INDEX_UPDATE_DELAY = -1;
  long DEFAULT_EXPIRY_MILLIS = -1;

  StoreType[] store() default {StoreType.MEMORY, StoreType.DISK, StoreType.REDIS};

  /** Whether to automatically initialize a created store. */
  boolean autoInit() default true;

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
  long indexUpdateDelaySeconds() default DEFAULT_INDEX_UPDATE_DELAY;

  /** Whether {@link MockClock} should automatically advance itself by 1 second. */
  boolean autoAdvanceClock() default true;

  long editorLockExpiryMillis() default DEFAULT_EXPIRY_MILLIS;

  long staleEntryExpiryMillis() default DEFAULT_EXPIRY_MILLIS;

  enum StoreType {
    MEMORY,
    DISK,
    REDIS
  }

  enum FileSystemType {
    IN_MEMORY,
    SYSTEM,
    EMULATED_WINDOWS, // See WindowsEmulatingFileSystem.
    NONE
  }

  enum Execution {
    QUEUED {
      @Override
      public Executor newExecutor() {
        return new MockExecutor();
      }
    },
    SAME_THREAD {
      @Override
      public Executor newExecutor() {
        return FlowSupport.SYNC_EXECUTOR;
      }
    },
    ASYNC {
      @Override
      public Executor newExecutor() {
        return Executors.newCachedThreadPool();
      }
    };

    abstract Executor newExecutor();
  }
}
