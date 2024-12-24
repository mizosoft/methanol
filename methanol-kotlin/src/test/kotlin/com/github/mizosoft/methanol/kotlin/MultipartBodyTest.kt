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

package com.github.mizosoft.methanol.kotlin

import assertk.all
import assertk.assertThat
import assertk.assertions.*
import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.MimeBodyPublisher
import com.github.mizosoft.methanol.testing.RegistryFileTypeDetector
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

class MultipartBodyTest {
  @Test
  fun textPart() {
    val multipartBody = MultipartBody {
      "x" to "a"
    }
    assertThat(multipartBody.parts()).all {
      hasSize(1)
      first().given {
        assertThat(it.bodyPublisher().readString()).isEqualTo("a")
        assertThat(it.headers().map()).containsOnly(
          "Content-Disposition" to listOf("form-data; name=\"x\"")
        )
      }
    }
  }

  @Test
  fun textPartWithCharset() {
    val multipartBody = MultipartBody {
      "x".to("€", US_ASCII)
    }
    assertThat(multipartBody.parts()).all {
      hasSize(1)
      first().given {
        assertThat(it.bodyPublisher().readString()).isEqualTo("?")
        assertThat(it.headers().map()).containsOnly(
          "Content-Disposition" to listOf("form-data; name=\"x\"")
        )
      }
    }
  }

  @Test
  fun filePart(@TempDir dir: Path) {
    RegistryFileTypeDetector.register("nice", "text/nice".toMediaType())
    val file = Files.createTempFile(dir, javaClass.simpleName, ".nice").also {
      Files.writeString(it, "Hi there!")
    }
    val multipartBody = MultipartBody {
      "x" to file
    }
    assertThat(multipartBody.parts()).all {
      hasSize(1)
      first().given {
        assertThat(it.bodyPublisher()).given {
          assertThat(it.readString()).isEqualTo("Hi there!")
          assertThat(it).isInstanceOf(MimeBodyPublisher::class)
            .prop(MimeBodyPublisher::mediaType).isEqualTo("text/nice".toMediaType())
        }
        assertThat(it.headers().map()).containsOnly(
          "Content-Disposition" to listOf("form-data; name=\"x\"; filename=\"${file.fileName}\""),
        )
      }
    }
  }

  @Test
  fun filePartWithExplicitMediaType(@TempDir dir: Path) {
    val file = Files.createTempFile(dir, javaClass.simpleName, ".sassy").also {
      Files.writeString(it, "Oy!")
    }
    val multipartBody = MultipartBody {
      "x".to(file, "text/sassy".toMediaType())
    }
    assertThat(multipartBody.parts()).all {
      hasSize(1)
      first().given {
        assertThat(it.bodyPublisher()).given {
          assertThat(it.readString()).isEqualTo("Oy!")
          assertThat(it).isInstanceOf(MimeBodyPublisher::class)
            .prop(MimeBodyPublisher::mediaType).isEqualTo("text/sassy".toMediaType())
        }
        assertThat(it.headers().map()).containsOnly(
          "Content-Disposition" to listOf("form-data; name=\"x\"; filename=\"${file.fileName}\""),
        )
      }
    }
  }

  @Test
  fun addFormPart() {
    val multipartBody = MultipartBody {
      "x" to BodyPublishers.ofString("a")
      "y".to(BodyPublishers.ofString("a"), "y.txt")
      "z".to(BodyPublishers.ofString("a"), "z.txt", MediaType.TEXT_PLAIN)
    }
    assertThat(multipartBody.parts()).all {
      hasSize(3)
      index(0).given {
        assertThat(it.bodyPublisher().readString()).isEqualTo("a")
        assertThat(it.headers().map()).containsOnly(
          "Content-Disposition" to listOf("form-data; name=\"x\""),
        )
      }
      index(1).given {
        assertThat(it.bodyPublisher().readString()).isEqualTo("a")
        assertThat(it.headers().map()).containsOnly(
          "Content-Disposition" to listOf("form-data; name=\"y\"; filename=\"y.txt\""),
        )
      }
      index(2).given {
        assertThat(it.bodyPublisher()).given {
          assertThat(it.readString()).isEqualTo("a")
          assertThat(it).isInstanceOf(MimeBodyPublisher::class)
            .prop(MimeBodyPublisher::mediaType).isEqualTo("text/plain".toMediaType())
        }
        assertThat(it.headers().map()).containsOnly(
          "Content-Disposition" to listOf("form-data; name=\"z\"; filename=\"z.txt\""),
        )
      }
    }
  }

  @Test
  fun arbitraryPart() {
    val multipartBody = MultipartBody {
      +MultipartBodyPart(BodyPublishers.ofString("a")) {
        "X" to "A"
      }
    }
    assertThat(multipartBody.parts()).all {
      hasSize(1)
      first().given {
        assertThat(it.bodyPublisher().readString()).isEqualTo("a")
        assertThat(it.headers().map()).containsOnly("X" to listOf("A"))
      }
    }
  }
}
