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

package com.github.mizosoft.methanol.internal;

import static java.lang.String.format;

import com.google.errorprone.annotations.DoNotCall;
import com.google.errorprone.annotations.FormatMethod;
import com.google.errorprone.annotations.FormatString;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public class Validate {
  private Validate() {}

  public static void requireArgument(boolean argIsValid, String msg) {
    if (!argIsValid) {
      throw new IllegalArgumentException(msg);
    }
  }

  @FormatMethod
  public static void requireArgument(
      boolean argIsValid, @FormatString String msgFormat, @Nullable Object... args) {
    if (!argIsValid) {
      throw new IllegalArgumentException(format(msgFormat, args));
    }
  }

  public static void requireState(boolean stateIsValid, String msg) {
    if (!stateIsValid) {
      throw new IllegalStateException(msg);
    }
  }

  @FormatMethod
  public static void requireState(
      boolean stateIsValid, @FormatString String msgFormat, @Nullable Object... args) {
    if (!stateIsValid) {
      throw new IllegalStateException(format(msgFormat, args));
    }
  }

  /** Copied from checker-framework's {@code NullnessUtil} to avoid a runtime dependency. */
  @SuppressWarnings({
    "nullness", // Nullness utilities are trusted regarding nullness.
    "cast", // Casts look redundant if Nullness Checker is not run.
    "NullAway",
    "RedundantCast"
  })
  public static @EnsuresNonNull("#1") <T extends @Nullable Object> @NonNull T castNonNull(
      @Nullable T ref) {
    assert ref != null : "Misuse of castNonNull: called with a null argument";
    return (@NonNull T) ref;
  }

  @SuppressWarnings({"TypeParameterUnusedInFormals", "unused"})
  @DoNotCall("Always throws java.lang.UnsupportedOperationException")
  public static <T> T TODO() {
    throw new UnsupportedOperationException("not implemented");
  }
}
