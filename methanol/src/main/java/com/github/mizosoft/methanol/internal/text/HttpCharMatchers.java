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

package com.github.mizosoft.methanol.internal.text;

import static com.github.mizosoft.methanol.internal.text.CharMatcher.anyOf;
import static com.github.mizosoft.methanol.internal.text.CharMatcher.lettersOrDigits;
import static com.github.mizosoft.methanol.internal.text.CharMatcher.withinClosedRange;

/** Common {@code CharMatchers} for HTTP headers and the like. */
public class HttpCharMatchers {
  private HttpCharMatchers() {} // non-instantiable

  // header-field   = field-name ":" OWS field-value OWS
  //
  // field-name     = token
  // field-value    = *( field-content / obs-fold )

  // token          = 1*tchar
  // tchar          = "!" / "#" / "$" / "%" / "&" / "'" / "*"
  //                    / "+" / "-" / "." / "^" / "_" / "`" / "|" / "~"
  //                    / DIGIT / ALPHA
  //                    ; any VCHAR, except delimiters
  public static final CharMatcher TOKEN_MATCHER = anyOf("!#$%&'*+-.^_`|~").or(lettersOrDigits());

  // field-content  = field-vchar [ 1*( SP / HTAB ) field-vchar ]
  // field-vchar    = VCHAR / obs-text
  //
  // obs-fold       = CRLF 1*( SP / HTAB )
  //                ; obsolete line folding
  //                ; see Section 3.2.4
  // (obs-fold & obs-test will be forbidden)
  public static final CharMatcher FIELD_VALUE_MATCHER =
      withinClosedRange(0x21, 0x7E) // VCHAR
          .or(anyOf(" \t")); // ( SP / HTAB )

  // quoted-string  = DQUOTE *( qdtext / quoted-pair ) DQUOTE
  // qdtext         = HTAB / SP / %x21 / %x23-5B / %x5D-7E / obs-text
  // obs-text       = %x80-FF
  public static final CharMatcher QUOTED_TEXT_MATCHER =
      anyOf("\t !") // HTAB + SP + 0x21
          .or(withinClosedRange(0x23, 0x5B))
          .or(withinClosedRange(0x5D, 0x7E));

  // quoted-pair    = "\" ( HTAB / SP / VCHAR / obs-text )
  public static final CharMatcher QUOTED_PAIR_MATCHER =
      anyOf("\t ") // HTAB + SP
          .or(withinClosedRange(0x21, 0x7E)); // VCHAR

  //  OWS = *( SP / HTAB )
  public static final CharMatcher OWS_MATCHER = anyOf("\t ");

  // boundary     := 0*69<bchars> bcharnospace
  // bchars       := bcharnospace / " "
  // bcharnospace := DIGIT / ALPHA / "'" / "(" / ")" /
  //                 "+" / "_" / "," / "-" / "." ?
  //                 "/" / ":" / "=" / "?"
  public static final CharMatcher BOUNDARY_MATCHER = anyOf("'()+_,-./:=? ").or(lettersOrDigits());
}
