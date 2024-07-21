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

package com.github.mizosoft.methanol.store.redis;

import com.github.mizosoft.methanol.internal.Utils;
import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import io.lettuce.core.api.sync.RedisScriptingCommands;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

enum Script {
  COMMIT("/scripts/commit.lua", false),
  EDIT("/scripts/edit.lua", false),
  REMOVE("/scripts/remove.lua", false),
  REMOVE_ALL("/scripts/remove_all.lua", false),
  APPEND("/scripts/append.lua", false),
  SCAN_ENTRIES("/scripts/scan_entries.lua", true),
  GET_STALE_RANGE("/scripts/get_stale_range.lua", false);

  private final byte[] content;
  private final String shaHex;
  private final boolean isReadOnly;

  Script(String scriptPath, boolean isReadOnly) {
    this.content = load(scriptPath);
    this.shaHex = toHexString(newSha1Digest().digest(this.content));
    this.isReadOnly = isReadOnly;
  }

  <K, V> RunnableScript<K, V> evalOn(RedisScriptingCommands<K, V> commands) {
    return new RunnableScript<>(this, commands);
  }

  <K, V> AsyncRunnableScript<K, V> evalOn(RedisScriptingAsyncCommands<K, V> commands) {
    return new AsyncRunnableScript<>(this, commands);
  }

  private static byte[] load(String path) {
    try (var in = Script.class.getResourceAsStream(path)) {
      if (in == null) {
        throw new NoSuchFileException(path, null, "can't find resource");
      }
      return in.readAllBytes();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static MessageDigest newSha1Digest() {
    try {
      return MessageDigest.getInstance("SHA1");
    } catch (NoSuchAlgorithmException e) {
      throw new UnsupportedOperationException("SHA1 not available!", e);
    }
  }

  private static String toHexString(byte[] bytes) {
    var sb = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      char upperHex = Character.forDigit((b >> 4) & 0xf, 16);
      char lowerHex = Character.forDigit(b & 0xf, 16);
      sb.append(upperHex).append(lowerHex);
    }
    return sb.toString();
  }

  static final class RunnableScript<K, V> {
    private final Script script;
    private final RedisScriptingCommands<K, V> commands;

    RunnableScript(Script script, RedisScriptingCommands<K, V> commands) {
      this.script = script;
      this.commands = commands;
    }

    boolean getAsBoolean(List<K> keys, List<V> values) {
      return getAs(keys, values, ScriptOutputType.BOOLEAN, Boolean.class);
    }

    List<?> getAsMulti(List<K> keys, List<V> values) {
      return getAs(keys, values, ScriptOutputType.MULTI, List.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T getAs(
        List<K> keys, List<V> values, ScriptOutputType outputType, Class<T> rawReturnType) {
      var keysArray = (K[]) keys.toArray();
      var valuesArray = (V[]) values.toArray();
      try {
        return evalsha(keysArray, valuesArray, outputType, rawReturnType);
      } catch (RedisNoScriptException e) {
        return eval(keysArray, valuesArray, outputType, rawReturnType);
      }
    }

    private <T> T evalsha(
        K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
      return rawReturnType.cast(
          script.isReadOnly
              ? commands.evalshaReadOnly(script.shaHex, outputType, keysArray, valuesArray)
              : commands.evalsha(script.shaHex, outputType, keysArray, valuesArray));
    }

    private <T> T eval(
        K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
      return rawReturnType.cast(
          script.isReadOnly
              ? commands.evalReadOnly(script.content, outputType, keysArray, valuesArray)
              : commands.eval(script.content, outputType, keysArray, valuesArray));
    }
  }

  static final class AsyncRunnableScript<K, V> {
    private final Script script;
    private final RedisScriptingAsyncCommands<K, V> commands;

    AsyncRunnableScript(Script script, RedisScriptingAsyncCommands<K, V> commands) {
      this.script = script;
      this.commands = commands;
    }

    CompletableFuture<Long> getAsLong(List<K> keys, List<V> values) {
      return getAs(keys, values, ScriptOutputType.INTEGER, Long.class);
    }

    CompletableFuture<Boolean> getAsBoolean(List<K> keys, List<V> values) {
      return getAs(keys, values, ScriptOutputType.BOOLEAN, Boolean.class);
    }

    CompletableFuture<List<?>> getAsList(List<K> keys, List<V> values) {
      return getAs(keys, values, ScriptOutputType.MULTI, List.class)
          .thenApply(list -> (List<?>) list);
    }

    CompletableFuture<ByteBuffer> getAsValue(List<K> keys, List<V> values) {
      return getAs(keys, values, ScriptOutputType.VALUE, ByteBuffer.class);
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> getAs(
        List<K> keys, List<V> values, ScriptOutputType outputType, Class<T> rawReturnType) {
      var keysArray = (K[]) keys.toArray();
      var valuesArray = (V[]) values.toArray();
      return evalsha(keysArray, valuesArray, outputType, rawReturnType)
          .handle(
              (result, ex) -> {
                if (result != null) {
                  return CompletableFuture.completedFuture(result);
                } else if (Utils.getDeepCompletionCause(ex) instanceof RedisNoScriptException) {
                  return eval(keysArray, valuesArray, outputType, rawReturnType);
                } else {
                  return CompletableFuture.<T>failedFuture(ex);
                }
              })
          .thenCompose(Function.identity());
    }

    private <T> CompletableFuture<T> evalsha(
        K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
      var future =
          script.isReadOnly
              ? commands.evalshaReadOnly(script.shaHex, outputType, keysArray, valuesArray)
              : commands.evalsha(script.shaHex, outputType, keysArray, valuesArray);
      return future.thenApply(rawReturnType::cast).toCompletableFuture();
    }

    private <T> CompletableFuture<T> eval(
        K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
      var future =
          script.isReadOnly
              ? commands.evalReadOnly(script.content, outputType, keysArray, valuesArray)
              : commands.eval(script.content, outputType, keysArray, valuesArray);
      return future.thenApply(rawReturnType::cast).toCompletableFuture();
    }
  }
}
