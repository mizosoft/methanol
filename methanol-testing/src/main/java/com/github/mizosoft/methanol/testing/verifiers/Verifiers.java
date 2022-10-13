/*
 * Copyright (c) 2022 Moataz Abdelnasser
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

package com.github.mizosoft.methanol.testing.verifiers;

import com.github.mizosoft.methanol.BodyAdapter;
import com.github.mizosoft.methanol.testing.verifiers.DecoderVerifier.BodySubscriberVerifier;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodySubscriber;

public class Verifiers {
  private Verifiers() {}

  public static BodyPublisherVerifier verifyThat(BodyPublisher publisher) {
    return new BodyPublisherVerifier(publisher);
  }

  public static <T> BodySubscriberVerifier<T> verifyThat(BodySubscriber<T> subscriber) {
    return new BodySubscriberVerifier<>(subscriber);
  }

  public static RequestVerifier verifyThat(HttpRequest request) {
    return new RequestVerifier(request);
  }

  public static <T> ResponseVerifier<T> verifyThat(HttpResponse<T> response) {
    return new ResponseVerifier<>(response);
  }

  public static EncoderVerifier verifyThat(BodyAdapter.Encoder encoder) {
    return new EncoderVerifier(encoder);
  }

  public static DecoderVerifier verifyThat(BodyAdapter.Decoder decoder) {
    return new DecoderVerifier(decoder);
  }
}
