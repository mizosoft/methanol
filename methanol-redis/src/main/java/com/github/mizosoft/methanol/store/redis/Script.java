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

import static java.nio.charset.StandardCharsets.UTF_8;

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

  <K, V> RunnableScript<K, V> evalOn(RedisScriptingCommands<K, V> commands) {
    return isReadOnly
        ? RunnableScript.ofReadonly(this, commands)
        : RunnableScript.of(this, commands);
  }

  <K, V> AsyncRunnableScript<K, V> evalOn(RedisScriptingAsyncCommands<K, V> commands) {
    return isReadOnly
        ? AsyncRunnableScript.ofReadonly(this, commands)
        : AsyncRunnableScript.of(this, commands);
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

    boolean asBoolean(List<K> keys, List<V> values) {
      return as(keys, values, ScriptOutputType.BOOLEAN, Boolean.class);
    }

    List<?> asMulti(List<K> keys, List<V> values) {
      return as(keys, values, ScriptOutputType.MULTI, List.class);
    }

    @SuppressWarnings("unchecked")
    private <T> T as(
        List<K> keys, List<V> values, ScriptOutputType outputType, Class<T> rawReturnType) {
      var keysArray = (K[]) keys.toArray();
      var valuesArray = (V[]) values.toArray();
      try {
        return evalsha(keysArray, valuesArray, outputType, rawReturnType);
      } catch (RedisNoScriptException e) {
        return eval(keysArray, valuesArray, outputType, rawReturnType);
      }
    }

    abstract <T> T evalsha(
        K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType);

    abstract <T> T eval(
        K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType);

    static <K, V> RunnableScript<K, V> of(Script script, RedisScriptingCommands<K, V> commands) {
      return new RunnableScript<>() {
        @Override
        public <T> T evalsha(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
          return rawReturnType.cast(
              commands.evalsha(script.shaHex(), outputType, keysArray, valuesArray));
        }

        @Override
        public <T> T eval(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
          return rawReturnType.cast(
              commands.eval(script.content().getBytes(UTF_8), outputType, keysArray, valuesArray));
        }
      };
    }

    static <K, V> RunnableScript<K, V> ofReadonly(
        Script script, RedisScriptingCommands<K, V> commands) {
      return new RunnableScript<>() {
        @Override
        public <T> T evalsha(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
          return rawReturnType.cast(
              commands.evalshaReadOnly(script.shaHex(), outputType, keysArray, valuesArray));
        }

        @Override
        public <T> T eval(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
          return rawReturnType.cast(
              commands.evalReadOnly(
                  script.content().getBytes(UTF_8), outputType, keysArray, valuesArray));
        }
      };
    }
  }

  abstract static class AsyncRunnableScript<K, V> {
    private AsyncRunnableScript() {}

    CompletableFuture<Long> asLong(List<K> keys, List<V> values) {
      return as(keys, values, ScriptOutputType.INTEGER, Long.class);
    }

    CompletableFuture<Boolean> asBoolean(List<K> keys, List<V> values) {
      return as(keys, values, ScriptOutputType.BOOLEAN, Boolean.class);
    }

    CompletableFuture<List<?>> asMulti(List<K> keys, List<V> values) {
      return as(keys, values, ScriptOutputType.MULTI, List.class).thenApply(list -> (List<?>) list);
    }

    CompletableFuture<ByteBuffer> asValue(List<K> keys, List<V> values) {
      return as(keys, values, ScriptOutputType.VALUE, ByteBuffer.class);
    }

    @SuppressWarnings("unchecked")
    private <T> CompletableFuture<T> as(
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

    abstract <T> CompletableFuture<T> evalsha(
        K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType);

    abstract <T> CompletableFuture<T> eval(
        K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType);

    static <K, V> AsyncRunnableScript<K, V> of(
        Script script, RedisScriptingAsyncCommands<K, V> commands) {
      return new AsyncRunnableScript<>() {
        @Override
        public <T> CompletableFuture<T> evalsha(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
          return commands
              .evalsha(script.shaHex(), outputType, keysArray, valuesArray)
              .thenApply(rawReturnType::cast)
              .toCompletableFuture();
        }

        @Override
        public <T> CompletableFuture<T> eval(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
          return commands
              .eval(script.content().getBytes(UTF_8), outputType, keysArray, valuesArray)
              .thenApply(rawReturnType::cast)
              .toCompletableFuture();
        }
      };
    }

    static <K, V> AsyncRunnableScript<K, V> ofReadonly(
        Script script, RedisScriptingAsyncCommands<K, V> commands) {
      return new AsyncRunnableScript<>() {
        @Override
        public <T> CompletableFuture<T> evalsha(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
          return commands
              .evalshaReadOnly(script.shaHex(), outputType, keysArray, valuesArray)
              .thenApply(rawReturnType::cast)
              .toCompletableFuture();
        }

        @Override
        public <T> CompletableFuture<T> eval(
            K[] keysArray, V[] valuesArray, ScriptOutputType outputType, Class<T> rawReturnType) {
          return commands
              .evalReadOnly(script.content().getBytes(UTF_8), outputType, keysArray, valuesArray)
              .thenApply(rawReturnType::cast)
              .toCompletableFuture();
        }
      };
    }
  }
}
