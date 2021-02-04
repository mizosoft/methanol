/*
 * Copyright (c) 2019-2021 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing;

import com.github.mizosoft.methanol.internal.cache.DiskStore;
import com.github.mizosoft.methanol.internal.cache.DiskStore.Hash;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** {@code DiskStore.Hasher} allowing to explicitly set fake hash codes for some keys. */
public final class MockHasher implements DiskStore.Hasher {
  private final Map<String, Hash> mockHashCodes = new ConcurrentHashMap<>();

  MockHasher() {}

  @Override
  public Hash hash(String key) {
    // Fallback to default hasher if a fake hash is not set
    var mockHash = mockHashCodes.get(key);
    return mockHash != null ? mockHash : TRUNCATED_SHA_256.hash(key);
  }

  public void setHash(String key, long upperHashBits) {
    mockHashCodes.put(
        key, new Hash(ByteBuffer.allocate(80).putLong(upperHashBits).putShort((short) 0).flip()));
  }
}
