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

package com.github.mizosoft.methanol.internal.flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DemandTest {

  @Test
  void increase_throwsOnNonPositiveValues() {
    var d = new Demand();
    assertThrows(IllegalArgumentException.class, () -> d.increase(0));
    assertThrows(IllegalArgumentException.class, () -> d.increase(-1));
  }

  @Test
  void increase_doesNotExceedMaxLong() {
    var d = new Demand();
    d.increase(10);
    d.increase(Long.MAX_VALUE);
    d.increase(10);
    assertEquals(Long.MAX_VALUE, d.current());
  }

  @Test
  void increase_returnsTrueIfPreviouslyFulfilled() {
    var d = new Demand();
    d.increase(10);
    d.decreaseAndGet(5);
    d.decreaseAndGet(5);
    assertTrue(d.increase(1));
    assertFalse(d.increase(1));
  }

  @Test
  void decrease_throwsOnNegativeValues() {
    var d = new Demand();
    assertThrows(IllegalArgumentException.class, () -> d.decreaseAndGet(-1));
  }

  @Test
  void decrease_decreasesAtMostCurrentValue() {
    var d = new Demand();
    d.increase(10);
    d.decreaseAndGet(100);
    assertEquals(0, d.current());
  }

  @Test
  void decrease_returnsValueAfterDecreasing() {
    var d = new Demand();
    d.increase(10);
    assertEquals(5, d.decreaseAndGet(5));
  }
}
