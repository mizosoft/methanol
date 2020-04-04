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

import static com.github.mizosoft.methanol.BodyDecoder.Factory.installedBindings;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.mizosoft.methanol.BodyDecoder;
import com.github.mizosoft.methanol.blackbox.MyBodyDecoderFactory.MyDeflateBodyDecoderFactory;
import com.github.mizosoft.methanol.blackbox.MyBodyDecoderFactory.MyGzipBodyDecoderFactory;
import java.util.logging.Filter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BodyDecoderFactoryTest {

  private static final String SERVICE_LOGGER_NAME =
      "com.github.mizosoft.methanol.internal.spi.ServiceCache";

  // Must hold a strong ref to retain configuration during tests
  private static Logger serviceCacheLogger;

  private static RecordingFilter recordingFilter;
  private static Filter originalFilter;
  private static Level originalLevel;

  @BeforeAll
  static void prepareServiceLogger() {
    serviceCacheLogger = Logger.getLogger(SERVICE_LOGGER_NAME);
    System.out.println(serviceCacheLogger);
    System.out.println(serviceCacheLogger.getName());
    System.out.println(serviceCacheLogger.getLevel());
    originalLevel = serviceCacheLogger.getLevel();
    serviceCacheLogger.setLevel(Level.WARNING);
    originalFilter = serviceCacheLogger.getFilter();
    recordingFilter = new RecordingFilter();
    serviceCacheLogger.setFilter(recordingFilter);
  }

  @AfterAll
  static void resetServiceLogger() {
    serviceCacheLogger.setLevel(originalLevel);
    serviceCacheLogger.setFilter(originalFilter);
  }

  /** @see FailingBodyDecoderFactory */
  @Test
  void failingDecoderIsIgnoredAndLogged() {
    BodyDecoder.Factory.installedFactories(); // trigger service lookup
    assertNotNull(recordingFilter.record);
    assertEquals(Level.WARNING, recordingFilter.record.getLevel()); // that would suffice :)
  }

  @Test
  void userFactoryOverridesDefault() {
    var bindings = installedBindings();
    assertEquals(MyDeflateBodyDecoderFactory.class, bindings.get("deflate").getClass());
    assertEquals(MyGzipBodyDecoderFactory.class, bindings.get("gzip").getClass());
  }

  // Doesn't log but records what was last tried to be logged
  private static final class RecordingFilter implements Filter {

    LogRecord record;

    RecordingFilter() {}

    @Override
    public boolean isLoggable(LogRecord record) {
      System.out.println("isLoggable(" + record + ")");
      this.record = record;
      return false;
    }
  }
}
