/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.MimeBodyPublisher;
import com.github.mizosoft.methanol.TypeRef;
import java.net.http.HttpRequest.BodyPublishers;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AbstractBodyAdapterTest {
  @Test
  void isCompatibleWith_single() {
    var adapter = new BodyAdapterImpl(MediaType.of("text", "*"));
    assertThat(adapter.isCompatibleWith(MediaType.of("text", "plain"))).isTrue();
    assertThat(adapter.isCompatibleWith(MediaType.of("text", "html"))).isTrue();
    assertThat(adapter.isCompatibleWith(MediaType.of("application", "octet-stream"))).isFalse();
    assertThat(adapter.compatibleMediaTypes()).containsOnly(MediaType.of("text", "*"));
  }

  @Test
  void isCompatibleWith_multiple() {
    var adapter =
        new BodyAdapterImpl(MediaType.of("text", "plain"), MediaType.of("application", "json"));
    assertThat(adapter.isCompatibleWith(MediaType.of("text", "plain"))).isTrue();
    assertThat(adapter.isCompatibleWith(MediaType.of("application", "json"))).isTrue();
    assertThat(adapter.isCompatibleWith(MediaType.of("application", "*"))).isTrue();
    assertThat(adapter.isCompatibleWith(MediaType.of("application", "octet_stream"))).isFalse();
    assertThat(adapter.compatibleMediaTypes())
        .containsOnly(MediaType.of("text", "plain"), MediaType.of("application", "json"));
  }

  @Test
  void requireSupport() {
    var adapter =
        new AbstractBodyAdapter(MediaType.ANY) {
          @Override
          public boolean supportsType(TypeRef<?> type) {
            return List.class.isAssignableFrom(type.rawType());
          }
        };
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> adapter.requireSupport(Set.class));
  }

  @Test
  void requireCompatibleOrNull() {
    var adapter = new BodyAdapterImpl(MediaType.of("text", "plain"));
    assertThatExceptionOfType(UnsupportedOperationException.class)
        .isThrownBy(() -> adapter.requireCompatibleOrNull(MediaType.of("application", "json")));
  }

  @Test
  void attachMediaType() {
    var textPlain = MediaType.of("text", "plain");
    var source = BodyPublishers.ofString("hello there");
    var withMediaType = AbstractBodyAdapter.attachMediaType(source, textPlain);
    var withoutMediaType = AbstractBodyAdapter.attachMediaType(source, null);
    var withoutMediaType2 = AbstractBodyAdapter.attachMediaType(source, MediaType.of("text", "*"));
    assertThat(withMediaType).isInstanceOf(MimeBodyPublisher.class);
    assertThat(((MimeBodyPublisher) withMediaType).mediaType()).isEqualTo(textPlain);
    assertThat(withoutMediaType).isSameAs(source);
    assertThat(withoutMediaType2).isSameAs(source);
  }

  private static final class BodyAdapterImpl extends AbstractBodyAdapter {
    BodyAdapterImpl(MediaType... compatibleMediaTypes) {
      super(compatibleMediaTypes);
    }

    @Override
    public boolean supportsType(TypeRef<?> type) {
      return false;
    }
  }
}
