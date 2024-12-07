package com.github.mizosoft.methanol.samples.kotlin

import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.kotlin.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object GetPostJson {
  val client = Client {
    baseUri("https://api.github.com/")
    defaultHeaders {
      "Accept" to "application/vnd.github+json"
      "X-GitHub-Api-Version" to "2022-11-28"
    }
    adapterCodec {
      basic()
      +KotlinAdapter.Encoder(
        Json, MediaType.APPLICATION_JSON
      )
      +KotlinAdapter.Decoder(Json {
        ignoreUnknownKeys = true // For brevity, we'll skip most fields.
      }, MediaType.APPLICATION_JSON)
    }
  }

  @Serializable
  data class Repository(
    val description: String,
    @SerialName("full_name") val fullName: String
  )

  suspend fun runGet() {
    val response = client.get<List<Repository>>("users/mizosoft/starred?per_page=10")
    response.body().forEach { repo ->
      println("${repo.fullName}\n\t${repo.description}")
    }
  }

  @Serializable
  data class Markdown(
    val text: String,
    val context: String,
    val mode: String
  )

  suspend fun runPost() {
    val response: Response<String> = client.post("markdown") {
      body(
        Markdown(
          "this code very fast: #437",
          "torvalds/linux",
          "gfm"
        ),
        MediaType.APPLICATION_JSON
      )
    }
    println(response.body())
  }
}
