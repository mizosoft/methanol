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

package com.github.mizosoft.methanol.store.redis;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.async.RedisScriptingAsyncCommands;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.NoSuchFileException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

enum Script {
  COMMIT("/scripts/commit.lua", false),
  EDIT("/scripts/edit.lua", false),
  REMOVE("/scripts/remove.lua", false),
  REMOVE_ALL("/scripts/remove_all.lua", false),
  APPEND("/scripts/append.lua", false),
  SCAN_ENTRIES("/scripts/scan_entries.lua", true),
  GET_STALE_RANGE("/scripts/get_stale_range.lua", false);

  private final String content;
  private final String shaHex;
  private final boolean isReadOnly;

  Script(String scriptPath, boolean isReadOnly) {
    var contentBytes = load(scriptPath);
    this.content = UTF_8.decode(ByteBuffer.wrap(contentBytes)).toString();
    this.shaHex = toHexString(newSha1Digest().digest(contentBytes));
    this.isReadOnly = isReadOnly;
  }

  String content() {
    return content;
  }

  String shaHex() {
    return shaHex;
  }

  <K, V> RunnableScript<K, V> evalOn(RedisScriptingAsyncCommands<K, V> commands) {
    return isReadOnly
        ? RunnableScript.of(this, commands)
        : RunnableScript.ofReadonly(this, commands);
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

  abstract static class RunnableScript<K, V> {
    private RunnableScript() {}

    CompletableFuture<Long> asLong(List<K> keys, List<V> values) {
      return as(keys, values, ScriptOutputType.INTEGER);
    }

    CompletableFuture<Boolean> asBoolean(List<K> keys, List<V> values) {
      return as(keys, values, ScriptOutputType.BOOLEAN);
    }

    CompletableFuture<List<Object>> asMulti(List<K> keys, List<V> values) {
      return as(keys, values, ScriptOutputType.MULTI);
    }

    CompletableFuture<ByteBuffer> asValue(List<K> keys, List<V> values) {
      return as(keys, values, ScriptOutputType.VALUE);
    }

    CompletableFuture<Void> asVoid(List<K> keys, List<V> values) {
      return as(keys, values, ScriptOutputType.STATUS).thenRun(() -> {});
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> as(List<K> keys, List<V> values, ScriptOutputType outputType) {
      var keysArray = (K[]) keys.toArray();
      var valuesArray = (V[]) values.toArray();
      return this.<T>evalsha(keysArray, valuesArray, outputType)
          .handle(
              (reply, ex) -> {
                if (ex instanceof RedisNoScriptException) {
                  return this.<T>eval(keysArray, valuesArray, outputType);
                }
                return ex != null
                    ? CompletableFuture.<T>failedFuture(ex)
                    : CompletableFuture.completedFuture(reply);
              })
          .thenCompose(Function.identity())
          .toCompletableFuture();
    }

    abstract <T> CompletionStage<T> evalsha(
        K[] keysArray, V[] valuesArray, ScriptOutputType outputType);

    abstract <T> CompletionStage<T> eval(
        K[] keysArray, V[] valuesArray, ScriptOutputType outputType);

    static <K, V> RunnableScript<K, V> of(
        Script script, RedisScriptingAsyncCommands<K, V> commands) {
      return new RunnableScript<>() {
        @Override
        public <T> CompletionStage<T> evalsha(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType) {
          return commands.evalsha(script.shaHex(), outputType, keysArray, valuesArray);
        }

        @Override
        public <T> CompletionStage<T> eval(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType) {
          return commands.eval(
              script.content().getBytes(UTF_8), outputType, keysArray, valuesArray);
        }
      };
    }

    static <K, V> RunnableScript<K, V> ofReadonly(
        Script script, RedisScriptingAsyncCommands<K, V> commands) {
      return new RunnableScript<>() {
        @Override
        public <T> CompletionStage<T> evalsha(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType) {
          return commands.evalshaReadOnly(script.shaHex(), outputType, keysArray, valuesArray);
        }

        @Override
        public <T> CompletionStage<T> eval(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType) {
          return commands.evalReadOnly(
              script.content().getBytes(UTF_8), outputType, keysArray, valuesArray);
        }
      };
    }
  }
}
