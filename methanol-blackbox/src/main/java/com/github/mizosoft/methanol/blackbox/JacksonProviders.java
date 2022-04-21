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

package com.github.mizosoft.methanol.blackbox;

import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory;

public class JacksonProviders {
  static final ObjectMapper configuredMapper =
      JsonMapper.builder()
          .disable(MapperFeature.AUTO_DETECT_GETTERS)
          .disable(MapperFeature.AUTO_DETECT_SETTERS)
          .disable(MapperFeature.AUTO_DETECT_IS_GETTERS)
          .build()
          .setVisibility(PropertyAccessor.ALL, Visibility.ANY);

  private JacksonProviders() {}

  public static class EncoderProvider {
    private EncoderProvider() {}

    public static BodyAdapter.Encoder provider() {
      return JacksonAdapterFactory.createJsonEncoder(configuredMapper);
    }
  }

  public static class DecoderProvider {
    private DecoderProvider() {}

    public static BodyAdapter.Decoder provider() {
      return JacksonAdapterFactory.createJsonDecoder(configuredMapper);
    }
  }
}
