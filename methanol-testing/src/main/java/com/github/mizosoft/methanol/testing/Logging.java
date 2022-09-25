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

package com.github.mizosoft.methanol.testing;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

/** Utility methods related to logging during tests. */
public class Logging {
  // Hold strong references to disabled loggers so their configuration won't get GCed.
  @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
  private static final Set<Logger> disabledLoggers = new CopyOnWriteArraySet<>();

  private Logging() {}

  public static void disable(Class<?>... clazz) {
    disable(Stream.of(clazz).map(Class::getName).toArray(String[]::new));
  }

  public static void disable(String... names) {
    for (var name : names) {
      var logger = java.util.logging.Logger.getLogger(name);
      logger.setLevel(Level.OFF);
      disabledLoggers.add(logger);
    }
  }
}
