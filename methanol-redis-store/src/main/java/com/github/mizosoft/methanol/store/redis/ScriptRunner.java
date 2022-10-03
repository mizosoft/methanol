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

import io.lettuce.core.RedisNoScriptException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

class ScriptRunner<K, V> {
  private final StatefulRedisConnection<K, V> connection;

  ScriptRunner(StatefulRedisConnection<K, V> connection) {
    this.connection = connection;
  }

  <T> T run(Script script, ScriptOutputType outputType, K[] keys, V... values) {
    var commands = connection.sync();
    try {
      return commands.evalsha(script.sha1(), outputType, keys, values);
    } catch (RedisNoScriptException e) {
      return commands.eval(script.encodedBytes(), outputType, keys, values);
    }
  }

  <T> CompletableFuture<T> runAsync(
      Script script, ScriptOutputType outputType, K[] keys, V... values) {
    var commands = connection.async();
    return commands
        .<T>evalsha(script.sha1(), outputType, keys, values)
        .handle(
            (reply, error) -> {
              if (error instanceof RedisNoScriptException) {
                return commands.<T>eval(script.encodedBytes(), outputType, keys, values);
              }
              return error != null
                  ? CompletableFuture.<T>failedFuture(error)
                  : CompletableFuture.completedFuture(reply);
            })
        .thenCompose(Function.identity())
        .toCompletableFuture();
  }
}
