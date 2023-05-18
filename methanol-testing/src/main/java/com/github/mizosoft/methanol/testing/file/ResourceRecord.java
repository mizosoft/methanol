/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing.file;

import static java.util.Objects.requireNonNull;

import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

final class ResourceRecord {
  final Path path;
  final ResourceType type;
  final Set<? extends OpenOption> openOptions;
  final List<StackFrame> stackTrace;

  enum ResourceType {
    FILE_CHANNEL,
    ASYNC_FILE_CHANNEL,
    DIRECTORY_STREAM
  }

  ResourceRecord(Path path, ResourceType type, Set<? extends OpenOption> openOptions) {
    this.path = requireNonNull(path);
    this.type = requireNonNull(type);
    this.openOptions = Set.copyOf(openOptions);

    var stackTrace =
        StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE)
            .walk(stream -> stream.collect(Collectors.toList()));
    // Discard this constructor's frame.
    if (!stackTrace.isEmpty() && stackTrace.get(0).getDeclaringClass() == ResourceRecord.class) {
      stackTrace.remove(0);
    }
    this.stackTrace = Collections.unmodifiableList(stackTrace);
  }

  private ResourceRecord(
      Path path,
      ResourceType type,
      Set<? extends OpenOption> openOptions,
      List<StackFrame> stackTrace) {
    this.path = path;
    this.type = type;
    this.openOptions = openOptions;
    this.stackTrace = stackTrace;
  }

  ResourceRecord withPath(Path path) {
    return new ResourceRecord(path, type, openOptions, stackTrace);
  }

  Throwable toThrowable() {
    var message = "<" + path + ">: " + type;
    if (!openOptions.isEmpty()) {
      message += " opened with " + openOptions;
    }
    var throwable = new Throwable(message);
    throwable.setStackTrace(
        stackTrace.stream().map(StackFrame::toStackTraceElement).toArray(StackTraceElement[]::new));
    return throwable;
  }

  @Override
  public String toString() {
    return String.format(
        "<%s>: %s %n\tat %s",
        path,
        type,
        stackTrace.stream()
            .map(StackFrame::toString)
            .collect(Collectors.joining(System.lineSeparator() + "\tat ")));
  }
}
