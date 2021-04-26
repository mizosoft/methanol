/*
 * Copyright (c) 2021 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testutils.io.file;

import java.lang.StackWalker.Option;
import java.lang.StackWalker.StackFrame;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

final class ResourceRecord {
  final Path path;
  final ResourceType type;
  final List<StackFrame> stackTrace;
  final Set<? extends OpenOption> openOptions;

  enum ResourceType {
    FILE_CHANNEL,
    ASYNC_FILE_CHANNEL,
    DIRECTORY_STREAM;
  }

  ResourceRecord(Path path, ResourceType type, Set<? extends OpenOption> openOptions) {
    this.path = path;
    this.type = type;

    var stackTrace =
        StackWalker.getInstance(Option.RETAIN_CLASS_REFERENCE)
            .walk(stream -> stream.collect(Collectors.toList()));
    // Discard this constructor's frame
    if (!stackTrace.isEmpty() && stackTrace.get(0).getDeclaringClass() == ResourceRecord.class) {
      stackTrace.remove(0);
    }
    this.stackTrace = Collections.unmodifiableList(stackTrace);

    this.openOptions = Set.copyOf(openOptions);
  }

  ResourceRecord(
      Path path,
      ResourceType type,
      List<StackFrame> stackTrace,
      Set<? extends OpenOption> openOptions) {
    this.path = path;
    this.type = type;
    this.stackTrace = stackTrace;
    this.openOptions = openOptions;
  }

  ResourceRecord withPath(Path path) {
    return new ResourceRecord(path, type, stackTrace, openOptions);
  }

  Throwable toThrowableStackTrace() {
    var message = "<" + path + ">: " + type;
    if (!openOptions.isEmpty()) {
      message += " opened with " + openOptions;
    }
    var throwableStackTrace = new Throwable(message);
    throwableStackTrace.setStackTrace(
        stackTrace.stream().map(StackFrame::toStackTraceElement).toArray(StackTraceElement[]::new));
    return throwableStackTrace;
  }

  @Override
  public String toString() {
    return String.format(
        "<%s>: %s, trace: %n%s",
        path,
        type,
        stackTrace.stream()
            .map(Objects::toString)
            .collect(Collectors.joining(System.lineSeparator(), "\tat ", "")));
  }
}
