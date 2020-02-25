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

package com.github.mizosoft.methanol.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MimeBodyPublisher;
import com.github.mizosoft.methanol.TypeReference;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AbstractBodyAdapterTest {

  @Test
  void isCompatibleWith_single() {
    var adapter = new BodyAdapterImpl(MediaType.of("text", "*"));
    assertTrue(adapter.isCompatibleWith(MediaType.of("text", "plain")));
    assertTrue(adapter.isCompatibleWith(MediaType.of("text", "html")));
    assertFalse(adapter.isCompatibleWith(MediaType.of("application", "octet-stream")));
    assertTrue(Set.of(MediaType.of("text", "*")).containsAll(adapter.compatibleMediaTypes()));
  }

  @Test
  void isCompatibleWith_multiple() {
    var adapter = new BodyAdapterImpl(
        MediaType.of("text", "plain"), MediaType.of("application", "json"));
    assertTrue(adapter.isCompatibleWith(MediaType.of("text", "plain")));
    assertTrue(adapter.isCompatibleWith(MediaType.of("application", "json")));
    assertTrue(adapter.isCompatibleWith(MediaType.of("application", "*")));
    assertFalse(adapter.isCompatibleWith(MediaType.of("application", "octet_stream")));
    var types = Set.of(MediaType.of("text", "plain"), MediaType.of("application", "json"));
    assertTrue(types.containsAll(adapter.compatibleMediaTypes()));
  }

  @Test
  void requireSupport() {
    var adapter = new AbstractBodyAdapter() {
      @Override
      public boolean supportsType(TypeReference<?> type) {
        return List.class.isAssignableFrom(type.rawType());
      }
    };
    assertThrows(UnsupportedOperationException.class,
        () -> adapter.requireSupport(Set.class));
  }

  @Test
  void requireCompatibleOrNull() {
    var adapter = new BodyAdapterImpl(MediaType.of("text", "plain"));
    assertThrows(UnsupportedOperationException.class,
        () -> adapter.requireCompatibleOrNull(MediaType.of("application", "json")));
  }

  @Test
  void attachMediaType() {
    var textPlain = MediaType.of("text", "plain");
    var source = BodyPublishers.ofString("hello there");
    var withMediaType = AbstractBodyAdapter.attachMediaType(source, textPlain);
    var withoutMediaType = AbstractBodyAdapter.attachMediaType(source, null);
    var withoutMediaType2 = AbstractBodyAdapter.attachMediaType(
        source, MediaType.of("text", "*"));
    assertTrue(withMediaType instanceof MimeBodyPublisher);
    assertEquals(textPlain, ((MimeBodyPublisher) withMediaType).mediaType());
    assertSame(source, withoutMediaType);
    assertSame(source, withoutMediaType2);
  }

  private static final class BodyAdapterImpl extends AbstractBodyAdapter {

    BodyAdapterImpl(MediaType... compatibleMediaTypes) {
      super(compatibleMediaTypes);
    }

    @Override
    public boolean supportsType(TypeReference<?> type) {
      return false;
    }
  }
}
