/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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
import static com.github.mizosoft.methanol.internal.Validate.requireState;
import static com.github.mizosoft.methanol.internal.text.HttpCharMatchers.OWS_MATCHER;
import static com.github.mizosoft.methanol.internal.text.HttpCharMatchers.QUOTED_PAIR_MATCHER;
import static com.github.mizosoft.methanol.internal.text.HttpCharMatchers.QUOTED_TEXT_MATCHER;
import static com.github.mizosoft.methanol.internal.text.HttpCharMatchers.TOKEN_MATCHER;

import java.nio.CharBuffer;

/** Tokenizes common delimited header values into individual components. */
public final class HeaderValueTokenizer {
  private final CharBuffer buffer;

  public HeaderValueTokenizer(String value) {
    this.buffer = CharBuffer.wrap(value);
  }

  public String nextToken() {
    buffer.mark();
    consumeIfPresent(TOKEN_MATCHER);
    int tokenLimit = buffer.position();
    buffer.reset();
    requireState(buffer.position() < tokenLimit, "expected a token at %d", buffer.position());
    int originalLimit = buffer.limit();
    var token = buffer.limit(tokenLimit).toString();
    buffer.position(tokenLimit).limit(originalLimit);
    return token;
  }

  public String nextTokenOrQuotedString() {
    return consumeCharIfPresent('"') ? finishQuotedString() : nextToken();
  }

  public void consumeIfPresent(CharMatcher matcher) {
    while (buffer.hasRemaining() && matcher.matches(buffer.get(buffer.position()))) {
      buffer.get(); // consume
    }
  }

  public boolean consumeCharIfPresent(char c) {
    if (buffer.hasRemaining() && buffer.get(buffer.position()) == c) {
      buffer.get(); // consume
      return true;
    }
    return false;
  }

  public void requireCharacter(char c) {
    requireState(getCharacter() == c, "expected a %c at %d", c, buffer.position() - 1);
  }

  public char getCharacter() {
    requireState(buffer.hasRemaining(), "expected more input");
    return buffer.get();
  }

  public boolean hasRemaining() {
    return buffer.hasRemaining();
  }

  public boolean consumeDelimiter(char delimiterChar) {
    // 1*( OWS <delimiterChar> OWS )
    if (hasRemaining()) {
      consumeIfPresent(OWS_MATCHER);
      requireCharacter(delimiterChar); // First delimiter must exist
      // Ignore dangling delimiters, https://github.com/google/guava/issues/1726
      do {
        consumeIfPresent(OWS_MATCHER);
      } while (consumeCharIfPresent(delimiterChar));
    }
    return hasRemaining();
  }

  private String finishQuotedString() {
    var unescaped = new StringBuilder();
    while (!consumeCharIfPresent('"')) {
      char c = getCharacter();
      requireArgument(
          QUOTED_TEXT_MATCHER.matches(c) || c == '\\',
          "illegal char %#x in a quoted-string",
          (int) c);
      if (c == '\\') { // quoted-pair
        c = getCharacter();
        requireArgument(
            QUOTED_PAIR_MATCHER.matches(c), "illegal char %#x in a quoted-pair", (int) c);
      }
      unescaped.append(c);
    }
    return unescaped.toString();
  }
}
