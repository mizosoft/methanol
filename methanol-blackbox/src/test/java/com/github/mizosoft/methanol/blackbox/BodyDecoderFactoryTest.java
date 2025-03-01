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

package com.github.mizosoft.methanol.blackbox;

import static com.github.mizosoft.methanol.BodyDecoder.Factory.installedBindings;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.blackbox.support.FailingBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.support.MyBodyDecoderFactory.MyDeflateBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.support.MyBodyDecoderFactory.MyGzipBodyDecoderFactory;
import com.github.mizosoft.methanol.testing.Logging;
import org.junit.jupiter.api.Test;

class BodyDecoderFactoryTest {
  static {
    // Do not log service loader failures
    Logging.disable("com.github.mizosoft.methanol.internal.spi.ServiceCache");
  }

  @Test
  void failingDecoderIsIgnored() {
    BodyDecoder.Factory.installedFactories(); // trigger service lookup if not yet done
    assertEquals(1, FailingBodyDecoderFactory.constructorCalls.get());
  }

  @Test
  void userFactoryOverridesDefault() {
    var bindings = installedBindings();
    assertEquals(MyDeflateBodyDecoderFactory.class, bindings.get("deflate").getClass());
    assertEquals(MyGzipBodyDecoderFactory.class, bindings.get("gzip").getClass());
  }
}
