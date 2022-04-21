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

package com.github.mizosoft.methanol.benchmarks;

import static com.github.mizosoft.methanol.benchmarks.BenchmarkUtils.ARRAY_OF_OBJECTS;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.mizosoft.methanol.MediaType;
import com.github.mizosoft.methanol.adapter.jackson.JacksonAdapterFactory;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Map;

@SuppressWarnings("unused")
public enum JsonHandler {
  ASYNC_PARSER {
    @Override
    BodySubscriber<List<Map<String, Object>>> createSubscriber(JsonMapper mapper, Charset charset) {
      return JacksonAdapterFactory.createJsonDecoder(mapper)
          .toObject(ARRAY_OF_OBJECTS, MediaType.APPLICATION_JSON.withCharset(charset));
    }
  },
  BYTE_ARRAY_PARSER {
    @Override
    BodySubscriber<List<Map<String, Object>>> createSubscriber(JsonMapper mapper, Charset charset) {
      return new ByteArrayJacksonDecoder(mapper, MediaType.APPLICATION_JSON)
          .toObject(ARRAY_OF_OBJECTS, MediaType.APPLICATION_JSON.withCharset(charset));
    }
  };

  abstract BodySubscriber<List<Map<String, Object>>> createSubscriber(
      JsonMapper mapper, Charset charset);
}
