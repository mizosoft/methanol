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

package com.github.mizosoft.methanol.testing;


import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A {@code Clock} that can be advanced manually or automatically with a specified duration. */
public final class MockClock extends Clock {
  private final ZoneId zoneId;
  private final Instant inception;
  private final AtomicReference<Instant[]> now;

  private volatile @Nullable Duration autoAdvance;

  /** Listener invoked with when the clock advances itself. */
  private volatile @Nullable BiConsumer<Instant /* beforeAdvance */, Duration /* ticks */>
      tickListener;

  public MockClock() {
    this(Instant.parse("2021-01-01T00:00:00.00Z"));
  }

  public MockClock(Instant inception) {
    this(ZoneOffset.UTC, inception);
  }

  public MockClock(ZoneId zoneId, Instant inception) {
    this.zoneId = zoneId;
    this.inception = inception;
    this.now = new AtomicReference<>(new Instant[] {inception});
  }

  @Override
  public ZoneId getZone() {
    return zoneId;
  }

  @Override
  public MockClock withZone(ZoneId zone) {
    return new MockClock(zoneId, instant());
  }

  @Override
  public Instant instant() {
    var ticks = autoAdvance;
    return ticks != null ? getAndAdvance(ticks) : peekInstant();
  }

  public void onTick(@Nullable BiConsumer<Instant, Duration> listener) {
    tickListener = listener;
  }

  /** Returns the clock's time without advancing it. */
  @SuppressWarnings("NullAway")
  public Instant peekInstant() {
    return now.get()[0];
  }

  public Instant inception() {
    return inception;
  }

  public void advance(Duration ticks) {
    getAndAdvance(ticks);
  }

  @SuppressWarnings("NullAway")
  private Instant getAndAdvance(Duration ticks) {
    while (true) {
      var instant = now.get();
      if (now.compareAndSet(instant, new Instant[] {instant[0].plus(ticks)})) {
        var listener = tickListener;
        if (listener != null) {
          listener.accept(instant[0], ticks);
        }
        return instant[0];
      }
    }
  }

  public void advanceSeconds(long seconds) {
    advance(Duration.ofSeconds(seconds));
  }

  public void autoAdvance(@Nullable Duration ticks) {
    this.autoAdvance = ticks;
  }
}
