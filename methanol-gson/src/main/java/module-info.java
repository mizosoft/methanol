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

import com.github.mizosoft.methanol.adapter.gson.GsonAdapterFactory;

/**
 * Provides {@link com.github.mizosoft.methanol.BodyAdapter.Encoder} and {@link
 * com.github.mizosoft.methanol.BodyAdapter.Decoder} implementations for JSON using the Gson
 * library. Note that, for the sake of configurability, the adapters are not service-provided by
 * default. You will need to explicitly declare service-providers that delegate to the instances
 * created by {@link GsonAdapterFactory}.
 */
module methanol.adapter.gson {
  requires transitive methanol;
  requires transitive com.google.gson;
  requires static org.checkerframework.checker.qual;

  exports com.github.mizosoft.methanol.adapter.gson;
}
