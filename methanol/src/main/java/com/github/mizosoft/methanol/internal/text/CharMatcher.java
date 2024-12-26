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

package com.github.mizosoft.methanol.internal.text;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;

/** Char matching API to use internally. */
public interface CharMatcher {
  boolean matches(char c);

  default boolean allMatch(CharSequence s) {
    return s.chars().allMatch(i -> matches((char) i));
  }

  default CharMatcher or(CharMatcher other) {
    return c -> matches(c) || other.matches(c);
  }

  static CharMatcher letters() {
    return c -> {
      char lower = Character.toLowerCase(c);
      return lower >= 'a' && lower <= 'z';
    };
  }

  static CharMatcher digits() {
    return c -> c >= '0' && c <= '9';
  }

  static CharMatcher lettersOrDigits() {
    return letters().or(digits());
  }

  static CharMatcher anyOf(String chars) {
    return c -> chars.indexOf(c) >= 0;
  }

  static CharMatcher withinClosedRange(int c1, int c2) {
    requireArgument((c1 | c2 | (c2 - c1)) >= 0, "illegal range [%d, %d]", c1, c2);
    return c -> c >= c1 && c <= c2;
  }

  static CharMatcher is(int c) {
    return x -> c == x;
  }
}
