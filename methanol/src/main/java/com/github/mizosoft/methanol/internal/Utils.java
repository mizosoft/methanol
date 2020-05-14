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

package com.github.mizosoft.methanol.internal;

import static com.github.mizosoft.methanol.internal.Validate.requireArgument;
import static com.github.mizosoft.methanol.internal.text.CharMatcher.alphaNum;
import static com.github.mizosoft.methanol.internal.text.CharMatcher.chars;
import static com.github.mizosoft.methanol.internal.text.CharMatcher.closedRange;
import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.text.CharMatcher;
import java.nio.ByteBuffer;
import java.time.Duration;

/** Miscellaneous utilities. */
public class Utils {

  // header-field   = field-name ":" OWS field-value OWS
  //
  // field-name     = token
  // field-value    = *( field-content / obs-fold )

  // token          = 1*tchar
  // tchar          = "!" / "#" / "$" / "%" / "&" / "'" / "*"
  //                    / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~"
  //                    / DIGIT / ALPHA
  //                    ; any VCHAR, except delimiters
  public static final CharMatcher TOKEN_MATCHER =
      chars("!#$%&'*+-.^_`|~")
          .or(alphaNum());

  // field-content  = field-vchar [ 1*( SP / HTAB ) field-vchar ]
  // field-vchar    = VCHAR / obs-text
  //
  // obs-fold       = CRLF 1*( SP / HTAB )
  //                ; obsolete line folding
  //                ; see Section 3.2.4
  // (obs-fold & obs-test will be forbidden)
  public static final CharMatcher FIELD_VALUE_MATCHER =
      closedRange(0x21, 0x7E) // VCHAR
          .or(chars(" \t"));  // ( SP / HTAB )

  private Utils() {} // non-instantiable

  public static boolean isValidToken(String token) {
    return !token.isEmpty() && TOKEN_MATCHER.allMatch(token);
  }

  public static void validateHeaderName(String name) {
    requireNonNull(name);
    requireArgument(isValidToken(name), "illegal header name: '%s'", name);
  }

  public static void validateHeaderValue(String value) {
    requireNonNull(value);
    requireArgument(FIELD_VALUE_MATCHER.allMatch(value), "illegal header value: '%s'", value);
  }

  public static void validateHeader(String name, String value) {
    validateHeaderName(name);
    validateHeaderValue(value);
  }

  public static void requirePositiveDuration(Duration timeout) {
    requireNonNull(timeout);
    requireArgument(
        !(timeout.isNegative() || timeout.isZero()),
        "non-positive duration: %s", timeout);
  }

  public static int copyRemaining(ByteBuffer src, ByteBuffer dst) {
    int toCopy = Math.min(src.remaining(), dst.remaining());
    int srcLimit = src.limit();
    src.limit(src.position() + toCopy);
    dst.put(src);
    src.limit(srcLimit);
    return toCopy;
  }
}
