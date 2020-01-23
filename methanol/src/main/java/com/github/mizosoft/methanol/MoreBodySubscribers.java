/*
 * MIT License
 *
 * Copyright (c) 2019 Moataz Abdelnasser
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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.extensions.ByteChannelSubscriber;
import java.io.Reader;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.channels.Channels;
import java.nio.channels.InterruptibleChannel;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.Charset;

/**
 * Provides additional {@link BodySubscriber} implementations.
 */
public class MoreBodySubscribers {

  /**
   * Returns a completed {@code BodySubscriber} of {@link ReadableByteChannel} that reads the
   * response body. The channel returned by the subscriber is {@link InterruptibleChannel
   * interruptible}.
   *
   * <p>To ensure proper release of resources, the channel should be fully consumed until EOF is
   * reached. If such consumption cannot be guaranteed, either the channel should be eventually
   * closed or the thread which is blocked on reading the channel should be interrupted. Note
   * however that doing so will render the underlying connection unusable for subsequent requests.
   */
  public static BodySubscriber<ReadableByteChannel> ofByteChannel() {
    return new ByteChannelSubscriber();
  }

  /**
   * Returns a completed {@code BodySubscriber} of {@link Reader} that reads the response body as a
   * stream of characters decoded using the given charset.
   *
   * <p>To ensure proper release of resources, the reader should be fully consumed until EOF is
   * reached. If such consumption cannot be guaranteed, the reader should be eventually closed. Note
   * however that doing so will render the underlying connection unusable for subsequent requests.
   *
   * @param charset the charset with which the response bytes are decoded
   */
  public static BodySubscriber<Reader> ofReader(Charset charset) {
    requireNonNull(charset);
    return BodySubscribers.mapping(ofByteChannel(), ch -> Channels.newReader(ch, charset));
  }
}
