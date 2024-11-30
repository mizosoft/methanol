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

package com.github.mizosoft.methanol.kotlin

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.testing.MockWebServerExtension
import com.github.mizosoft.methanol.testing.verifiers.Verifiers.verifyThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.extension.ExtendWith
import java.nio.charset.StandardCharsets.UTF_8
import kotlin.test.Test

@ExtendWith(MockWebServerExtension::class)
class FetchTest(private val server: MockWebServer) {
  private val serverUri = server.url("/").toString()

  @Test
  fun fetchGet() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.get<String>(serverUri)
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest().method).isEqualTo("GET")
  }

  @Test
  fun fetchGetWithBodyHandler() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.get(serverUri, BodyHandlers.ofString())
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest().method).isEqualTo("GET")
  }


  @Test
  fun fetchGetWithFetch() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.fetch<String>(serverUri) {
        GET()
      }
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest().method).isEqualTo("GET")
  }

  @Test
  fun fetchGetWithFetchImplicitly() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.fetch<String>(serverUri)
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest().method).isEqualTo("GET")
  }

  @Test
  fun fetchGetWithFetchExplicitly() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.fetch<String>(serverUri) {
        GET()
      }
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest().method).isEqualTo("GET")
  }

  @Test
  fun fetchDelete() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse())
    val response = runBlocking {
      client.delete<Unit>(serverUri)
    }
    verifyThat(response).hasCode(200).hasBody(Unit)
    assertThat(server.takeRequest().method).isEqualTo("DELETE")
  }

  @Test
  fun fetchDeleteWithBodyHandler() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse())
    val response = runBlocking {
      client.delete(serverUri, BodyHandlers.replacing(Unit))
    }
    verifyThat(response).hasCode(200).hasBody(Unit)
    assertThat(server.takeRequest().method).isEqualTo("DELETE")
  }

  @Test
  fun fetchDeleteWithFetch() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse())
    val response = runBlocking {
      client.fetch<Unit>(serverUri) {
        DELETE()
      }
    }
    verifyThat(response).hasCode(200).hasBody(Unit)
    assertThat(server.takeRequest().method).isEqualTo("DELETE")
  }

  @Test
  fun fetchPost() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.post<String>(serverUri) {
        body("Ditto", MediaType.TEXT_PLAIN)
      }
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest()).given {
      assertThat(it.method).isEqualTo("POST")
      assertThat(it.getHeader("Content-Type")).isEqualTo("text/plain")
      assertThat(it.body.readString(UTF_8)).isEqualTo("Ditto")
    }
  }

  @Test
  fun fetchPostWithBodyPublisherAndBodyHandler() {
    val client = Client {}
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.post(serverUri, BodyHandlers.ofString()) {
        body(BodyPublishers.ofString("Ditto"), MediaType.TEXT_PLAIN)
      }
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest()).given {
      assertThat(it.method).isEqualTo("POST")
      assertThat(it.getHeader("Content-Type")).isEqualTo("text/plain")
      assertThat(it.body.readString(UTF_8)).isEqualTo("Ditto")
    }
  }

  @Test
  fun fetchPostWithFetch() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.fetch<String>(serverUri) {
        POST {
          body("Ditto", MediaType.TEXT_PLAIN)
        }
      }
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest()).given {
      assertThat(it.method).isEqualTo("POST")
      assertThat(it.getHeader("Content-Type")).isEqualTo("text/plain")
      assertThat(it.body.readString(UTF_8)).isEqualTo("Ditto")
    }
  }

  @Test
  fun fetchPut() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.put<String>(serverUri) {
        body("Ditto", MediaType.TEXT_PLAIN)
      }
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest()).given {
      assertThat(it.method).isEqualTo("PUT")
      assertThat(it.getHeader("Content-Type")).isEqualTo("text/plain")
      assertThat(it.body.readString(UTF_8)).isEqualTo("Ditto")
    }
  }


  @Test
  fun fetchPutWithBodyPublisherAndBodyHandler() {
    val client = Client {}
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.put(serverUri, BodyHandlers.ofString()) {
        body(BodyPublishers.ofString("Ditto"), MediaType.TEXT_PLAIN)
      }
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest()).given {
      assertThat(it.method).isEqualTo("PUT")
      assertThat(it.getHeader("Content-Type")).isEqualTo("text/plain")
      assertThat(it.body.readString(UTF_8)).isEqualTo("Ditto")
    }
  }

  @Test
  fun fetchPutWithFetch() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.fetch<String>(serverUri) {
        PUT {
          body("Ditto", MediaType.TEXT_PLAIN)
        }
      }
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest()).given {
      assertThat(it.method).isEqualTo("PUT")
      assertThat(it.getHeader("Content-Type")).isEqualTo("text/plain")
      assertThat(it.body.readString(UTF_8)).isEqualTo("Ditto")
    }
  }

  @Test
  fun fetchPatch() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.patch<String>(serverUri) {
        body("Ditto", MediaType.TEXT_PLAIN)
      }
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest()).given {
      assertThat(it.method).isEqualTo("PATCH")
      assertThat(it.getHeader("Content-Type")).isEqualTo("text/plain")
      assertThat(it.body.readString(UTF_8)).isEqualTo("Ditto")
    }
  }

  @Test
  fun fetchPatchWithBodyPublisherAndBodyHandler() {
    val client = Client {}
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.patch(serverUri, BodyHandlers.ofString()) {
        body(BodyPublishers.ofString("Ditto"), MediaType.TEXT_PLAIN)
      }
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest()).given {
      assertThat(it.method).isEqualTo("PATCH")
      assertThat(it.getHeader("Content-Type")).isEqualTo("text/plain")
      assertThat(it.body.readString(UTF_8)).isEqualTo("Ditto")
    }
  }

  @Test
  fun fetchPatchWithFetch() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.fetch<String>(serverUri) {
        PATCH {
          body("Ditto", MediaType.TEXT_PLAIN)
        }
      }
    }
    verifyThat(response).hasCode(200).hasBody("Pikachu")
    assertThat(server.takeRequest()).given {
      assertThat(it.method).isEqualTo("PATCH")
      assertThat(it.getHeader("Content-Type")).isEqualTo("text/plain")
      assertThat(it.body.readString(UTF_8)).isEqualTo("Ditto")
    }
  }

  @Test
  fun fetchRequest() {
    val client = Client {
      adapterCodec {
        basic()
      }
    }
    server.enqueue(MockResponse().setBody("Pikachu"))
    val response = runBlocking {
      client.fetch<String>(Request {
        uri(serverUri)
        POST {
          formBody {
            "x" to "a"
          }
        }
      })
    }
    assertThat(response.body()).isEqualTo("Pikachu")
    assertThat(server.takeRequest()).given {
      assertThat(it.method).isEqualTo("POST")
      assertThat(
        it.getHeader("Content-Type")?.toMediaType()
      ).isEqualTo(MediaType.APPLICATION_FORM_URLENCODED)
      assertThat(it.body.readString(UTF_8)).isEqualTo("x=a")
    }
  }

  @Test
  fun fetchBodyWithBodyHandler() {
    val client = Client {}
    server.enqueue(MockResponse().setBody("Pikachu"))
    var response = runBlocking {
      client.fetch(serverUri, BodyHandlers.ofString())
    }
    assertThat(response.body()).isEqualTo("Pikachu")
  }

  @Serializable
  data class Person(val name: String)

  @Test
  fun getJson() {
    val client = Client {
      adapterCodec {
        +KotlinAdapter.Decoder(Json, MediaType.APPLICATION_JSON)
      }
    }

    server.enqueue(
      MockResponse().setBody("""{"name": "Tony Stark"}""")
        .setHeader("Content-Type", "application/json")
    )
    val response = runBlocking {
      client.get<Person>(serverUri)
    }
    assertThat(response.body()).isEqualTo(Person("Tony Stark"))
  }

  @Test
  fun postJson() {
    val client = Client {
      adapterCodec {
        +KotlinAdapter.Encoder(Json, MediaType.APPLICATION_JSON)
        basic() // For handling Unit.
      }
    }

    server.enqueue(MockResponse())
    runBlocking {
      client.post<Unit>(serverUri) {
        body(Person("Tony Stark"), MediaType.APPLICATION_JSON)
      }
    }
    assertThat(server.takeRequest()).given {
      assertThat(it.body.readString(UTF_8)).isEqualTo("""{"name":"Tony Stark"}""")
      assertThat(it.getHeader("Content-Type")).isEqualTo("application/json")
    }
  }

  @Test
  fun postGetJson() {
    val client = Client {
      adapterCodec {
        +KotlinAdapter.Encoder(Json, MediaType.APPLICATION_JSON)
        +KotlinAdapter.Decoder(Json, MediaType.APPLICATION_JSON)
      }
    }

    server.enqueue(
      MockResponse().setBody("""{"name":"Tony Stark"}""")
        .setHeader("Content-Type", "application/json")
    )
    val response = runBlocking {
      client.post<Person>(serverUri) {
        body(Person("Tony Stark"), MediaType.APPLICATION_JSON)
      }
    }
    assertThat(response.body()).isEqualTo(Person("Tony Stark"))
    assertThat(server.takeRequest()).given {
      assertThat(it.body.readString(UTF_8)).isEqualTo("""{"name":"Tony Stark"}""")
      assertThat(it.getHeader("Content-Type")).isEqualTo("application/json")
    }
  }

  @Test
  fun postGetJsonList() {
    val client = Client {
      adapterCodec {
        +KotlinAdapter.Encoder(Json, MediaType.APPLICATION_JSON)
        +KotlinAdapter.Decoder(Json, MediaType.APPLICATION_JSON)
      }
    }

    server.enqueue(
      MockResponse().setBody("""[{"name":"Tony Stark"},{"name":"Steve Rogers"}]""")
        .setHeader("Content-Type", "application/json")
    )
    val response = runBlocking {
      client.post<List<Person>>(serverUri) {
        body<List<Person>>(
          listOf(Person("Tony Stark"), Person("Steve Rogers")),
          MediaType.APPLICATION_JSON
        )
      }
    }
    assertThat(response.body()).isEqualTo(listOf(Person("Tony Stark"), Person("Steve Rogers")))
    assertThat(server.takeRequest()).given {
      assertThat(it.body.readString(UTF_8)).isEqualTo("""[{"name":"Tony Stark"},{"name":"Steve Rogers"}]""")
      assertThat(it.getHeader("Content-Type")).isEqualTo("application/json")
    }
  }
}
