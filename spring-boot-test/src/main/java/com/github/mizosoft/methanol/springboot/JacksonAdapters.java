/*
 * Copyright (c) 2019, 2021 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.springboot;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.adapter.ForwardingDecoder;
import com.github.mizosoft.methanol.adapter.ForwardingEncoder;
import com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory;
import com.google.auto.service.AutoService;

public class JacksonAdapters {
  private static final ObjectMapper mapper = new ObjectMapper();

  private JacksonAdapters() {}

  @AutoService(BodyAdapter.Encoder.class)
  public static class JacksonEncoder extends ForwardingEncoder {
    public JacksonEncoder() {
      super(JacksonAdapterFactory.createJsonEncoder(mapper));
    }
  }

  @AutoService(BodyAdapter.Decoder.class)
  public static class JacksonDecoder extends ForwardingDecoder {
    public JacksonDecoder() {
      super(JacksonAdapterFactory.createJsonDecoder(mapper));
    }
  }
}
