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

package com.github.mizosoft.methanol.adapter.jackson.flux;

import static com.github.mizosoft.methanol.testing.TestUtils.load;
import static java.nio.charset.StandardCharsets.UTF_16;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import com.github.mizosoft.methanol.testing.BufferTokenizer;
import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.TestUtils;
import java.net.http.HttpResponse.BodySubscribers;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.SubmissionPublisher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ExecutorExtension.class)
class CharsetRecodingSubscriberTest {
  @Test
  void utf8ToUtf16(Executor executor) {
    var aladinText = new String(load(getClass(), "/aladin_utf8.txt"), UTF_8);
    var aladinBytesUtf16 = UTF_16.encode(aladinText);
    var subscriber =
        new CharsetRecodingSubscriber<>(BodySubscribers.ofString(UTF_8), UTF_16, UTF_8);
    int[] buffSizes = {1, 32, 555, 1024, 21, 77};
    int[] listSizes = {1, 3, 1};
    executor.execute(
        () -> {
          try (var publisher =
              new SubmissionPublisher<List<ByteBuffer>>(executor, Integer.MAX_VALUE)) {
            publisher.subscribe(subscriber);
            BufferTokenizer.tokenizeToLists(aladinBytesUtf16, buffSizes, listSizes)
                .forEach(publisher::submit);
          }
        });

    assertThat(subscriber.getBody())
        .succeedsWithin(Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS), STRING)
        .isEqualToNormalizingNewlines(aladinText);
  }
}
