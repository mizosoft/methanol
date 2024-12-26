/*
 * Copyright (c) 2024 Moataz Hussein
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

package com.github.mizosoft.methanol.brotli.internal;

import java.io.IOException;
import java.nio.ByteBuffer;

/** JNI wrapper for brotli decoder. */
class DecoderJNI {

  private static native ByteBuffer nativeCreate(long[] context);

  private static native void nativePush(long[] context, int length);

  private static native ByteBuffer nativePull(long[] context);

  private static native void nativeDestroy(long[] context);

  enum Status {
    ERROR,
    DONE,
    NEEDS_MORE_INPUT,
    NEEDS_MORE_OUTPUT,
    OK
  }

  static class Wrapper {

    private final long[] context = new long[3];
    private final ByteBuffer inputBuffer;
    private Status lastStatus = Status.NEEDS_MORE_INPUT;

    Wrapper(int inputBufferSize) throws IOException {
      this.context[1] = inputBufferSize;
      this.inputBuffer = nativeCreate(this.context);
      if (this.context[0] == 0) {
        throw new IOException("failed to initialize native brotli decoder");
      }
    }

    void push(int length) {
      if (length < 0) {
        throw new IllegalArgumentException("negative block length");
      }
      if (context[0] == 0) {
        throw new IllegalStateException("brotli decoder is already destroyed");
      }
      if (lastStatus != Status.NEEDS_MORE_INPUT && lastStatus != Status.OK) {
        throw new IllegalStateException("pushing input to decoder in " + lastStatus + " state");
      }
      if (lastStatus == Status.OK && length != 0) {
        throw new IllegalStateException("pushing input to decoder in OK state");
      }
      nativePush(context, length);
      parseStatus();
    }

    void parseStatus() {
      long status = context[1];
      if (status == 1) {
        lastStatus = Status.DONE;
      } else if (status == 2) {
        lastStatus = Status.NEEDS_MORE_INPUT;
      } else if (status == 3) {
        lastStatus = Status.NEEDS_MORE_OUTPUT;
      } else if (status == 4) {
        lastStatus = Status.OK;
      } else {
        lastStatus = Status.ERROR;
      }
    }

    Status getStatus() {
      return lastStatus;
    }

    ByteBuffer getInputBuffer() {
      return inputBuffer;
    }

    boolean hasOutput() {
      return context[2] != 0;
    }

    ByteBuffer pull() {
      if (context[0] == 0) {
        throw new IllegalStateException("brotli decoder is already destroyed");
      }
      if (lastStatus != Status.NEEDS_MORE_OUTPUT && !hasOutput()) {
        throw new IllegalStateException("pulling output from decoder in " + lastStatus + " state");
      }
      ByteBuffer result = nativePull(context);
      parseStatus();
      return result;
    }

    /** Releases native resources. */
    void destroy() {
      if (context[0] == 0) {
        throw new IllegalStateException("brotli decoder is already destroyed");
      }
      nativeDestroy(context);
      context[0] = 0;
    }
  }
}
