package com.github.mizosoft.methanol.samples.kotlin

import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.kotlin.Client
import com.github.mizosoft.methanol.kotlin.KotlinAdapter
import com.github.mizosoft.methanol.kotlin.Response
import com.github.mizosoft.methanol.kotlin.post
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.io.path.readBytes

object MultipartAndFormUploads {
  // You can get your own clien ID here: https://api.imgur.com/oauth2/addclient.
  val imgurClientId = System.getProperty("imgur.client.id")

  val client = Client {
    baseUri("https://api.imgur.com/3/")
    defaultHeaders {
      "Authorization" to "Client-ID $imgurClientId"
    }
    adapterCodec {
      basic()
      +KotlinAdapter.Decoder(Json {
        ignoreUnknownKeys = true
      }, MediaType.APPLICATION_JSON)
    }
  }

  @Serializable
  data class ImgurResponse<T>(val status: Int, val success: Boolean, val data: T?)

  @Serializable
  data class Image(val link: String)

  suspend fun multipartUpload() {
    val response: Response<ImgurResponse<Image>> = client.post("image") {
      multipartBody {
        "image" to Path.of("images/popcat.gif") // File's Content-Type will be looked-up automatically.
        "title" to "PopCat"
        "description" to "A cat that pops"
      }
    }
    require(response.body().success) {
      "Unsuccessful response: $response - ${response.body()}"
    }
    println("Uploaded: ${response.body().data!!.link}")
  }

  @OptIn(ExperimentalEncodingApi::class) // In order to use Kotlin's Base64.
  suspend fun formUpload() {
    val response: Response<ImgurResponse<Image>> = client.post("image") {
      formBody {
        "image" to Base64.Default.encode(Path.of("images/popcat.gif").readBytes())
        "type" to "base64"
        "title" to "PopCat"
        "description" to "A cat that pops"
      }
    }
    require(response.body().success) {
      "Unsuccessful response: $response - ${response.body()}"
    }
    println("Uploaded: ${response.body().data!!.link}")
  }
}
