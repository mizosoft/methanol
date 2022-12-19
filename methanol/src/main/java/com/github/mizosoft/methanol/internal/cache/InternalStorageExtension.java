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

package com.github.mizosoft.methanol.internal.cache;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.StorageExtension;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public interface InternalStorageExtension extends StorageExtension {
  Store createStore(Executor executor, int appVersion);

  CompletableFuture<? extends Store> createStoreAsync(Executor executor, int appVersion);

  static InternalStorageExtension singleton(Store store) {
    requireNonNull(store);
    return new InternalStorageExtension() {
      @Override
      public Store createStore(Executor executor, int appVersion) {
        return store;
      }

      @Override
      public CompletableFuture<? extends Store> createStoreAsync(
          Executor executor, int appVersion) {
        return CompletableFuture.completedFuture(store);
      }
    };
  }
}
