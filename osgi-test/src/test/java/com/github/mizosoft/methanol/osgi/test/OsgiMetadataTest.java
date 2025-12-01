/*
 * Copyright (c) 2025 Moataz Hussein
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

package com.github.mizosoft.methanol.osgi.test;

import static org.assertj.core.api.Assertions.assertThat;

import aQute.bnd.header.Parameters;
import aQute.bnd.osgi.Domain;
import aQute.bnd.osgi.Jar;
import java.util.List;

import com.github.mizosoft.methanol.testing.TestUtils;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

@Timeout(TestUtils.VERY_SLOW_TIMEOUT_SECONDS)
class OsgiMetadataTest {
  private static final String VERSION =
      System.getProperty("com.github.mizosoft.methanol.osgi.test.version");

  static List<String> bundles() {
    return List.copyOf(OsgiUtils.BUNDLE_PATHS.keySet());
  }

  @ParameterizedTest
  @MethodSource("bundles")
  void validateOsgiMetadata(String bundle) throws Exception {
    try (var jar = new Jar(OsgiUtils.BUNDLE_PATHS.get(bundle).toFile())) {
      var domain = Domain.domain(jar.getManifest());
      assertThat(domain.getBundleName()).isEqualTo(bundle);
      assertThat(domain.getBundleVersion()).isEqualTo(VERSION);
      validateExportPackage(domain.getExportPackage());
      validatePrivatePackage(domain.getPrivatePackage());
    }
  }

  @ParameterizedTest
  @MethodSource("bundles")
  void compileOnlyPackagesAreNotImported(String bundle) throws Exception {
    try (var jar = new Jar(OsgiUtils.BUNDLE_PATHS.get(bundle).toFile())) {
      var domain = Domain.domain(jar.getManifest());
      for (var key : domain.getExportPackage().keySet()) {
        assertThat(key).doesNotContain("org.checkerframework", "com.google.errorprone");
      }
    }
  }

  private void validateExportPackage(Parameters exportPackage) {
    for (var entry : exportPackage.entrySet()) {
      // Check that exported internal packages have x-internal & x-friends attributes.
      if (entry.getKey().contains(".internal")) {
        var attrs = entry.getValue();
        assertThat(attrs.get("x-internal:")).isEqualTo("true");
        assertThat(attrs.get("x-friends:")).isNotBlank();
      }
    }
  }

  private void validatePrivatePackage(Parameters privatePackage) {
    // Private packages should contain internal packages only.
    for (var pkgName : privatePackage.keySet()) {
      assertThat(pkgName).contains("internal");
    }
  }
}
