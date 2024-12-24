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

package com.github.mizosoft.methanol;

import static com.github.mizosoft.methanol.HttpStatus.isClientError;
import static com.github.mizosoft.methanol.HttpStatus.isInformational;
import static com.github.mizosoft.methanol.HttpStatus.isRedirection;
import static com.github.mizosoft.methanol.HttpStatus.isServerError;
import static com.github.mizosoft.methanol.HttpStatus.isSuccessful;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HttpStatusTest {
  @Test
  void informational() {
    assertTrue(isInformational(100));
    assertTrue(isInformational(150));
    assertTrue(isInformational(199));
    assertFalse(isInformational(200));
    assertFalse(isInformational(99));
    assertFalse(isInformational(Integer.MAX_VALUE));
    assertFalse(isInformational(Integer.MIN_VALUE));
  }

  @Test
  void successful() {
    assertTrue(isSuccessful(200));
    assertTrue(isSuccessful(250));
    assertTrue(isSuccessful(299));
    assertFalse(isSuccessful(300));
    assertFalse(isSuccessful(199));
    assertFalse(isSuccessful(Integer.MAX_VALUE));
    assertFalse(isSuccessful(Integer.MIN_VALUE));
  }

  @Test
  void redirection() {
    assertTrue(isRedirection(300));
    assertTrue(isRedirection(350));
    assertTrue(isRedirection(399));
    assertFalse(isRedirection(400));
    assertFalse(isRedirection(299));
    assertFalse(isRedirection(Integer.MAX_VALUE));
    assertFalse(isRedirection(Integer.MIN_VALUE));
  }

  @Test
  void clientError() {
    assertTrue(isClientError(400));
    assertTrue(isClientError(450));
    assertTrue(isClientError(499));
    assertFalse(isClientError(500));
    assertFalse(isClientError(399));
    assertFalse(isClientError(Integer.MAX_VALUE));
    assertFalse(isClientError(Integer.MIN_VALUE));
  }

  @Test
  void serverError() {
    assertTrue(isServerError(500));
    assertTrue(isServerError(550));
    assertTrue(isServerError(599));
    assertFalse(isServerError(600));
    assertFalse(isServerError(499));
    assertFalse(isServerError(Integer.MAX_VALUE));
    assertFalse(isServerError(Integer.MIN_VALUE));
  }
}
