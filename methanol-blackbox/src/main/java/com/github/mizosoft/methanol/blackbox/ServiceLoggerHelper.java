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


import java.util.logging.Level;
import java.util.logging.Logger;

/** Turns off ServiceCache logger during tests. */
public class ServiceLoggerHelper {

  private static final String SERVICE_LOGGER_NAME =
      "com.github.mizosoft.methanol.internal.spi.ServiceCache";

  private final Logger logger;
  private Level originalLevel;

  ServiceLoggerHelper() {
    this.logger = Logger.getLogger(SERVICE_LOGGER_NAME);
  }

  void turnOff() {
    // Do not log service loader failures.
    originalLevel = logger.getLevel();
    logger.setLevel(Level.OFF);
  }

  void reset() {
    logger.setLevel(originalLevel);
  }
}
