package com.github.mizosoft.methanol.samples.kotlin

import com.github.mizosoft.methanol.MediaType
import com.github.mizosoft.methanol.kotlin.*

object GetPostString {
  val client = Client {
    adapterCodec {
      basic()
    }
  }

  suspend fun runGet() {
    val response = client.get<String>("https://www.cl.cam.ac.uk/~mgk25/ucs/examples/UTF-8-demo.txt")
    require(response.isSuccessful()) { "Unsuccessful response: $response - ${response.body()}" }
    println(response.body())
  }

  suspend fun runPost() {
    val response: Response<String> = client.post("https://api.github.com/markdown/raw") {
      body(
        """
          > He who has a ***why*** to live can bear almost any ***how***.
          >  - Friedrich Nietzsche
        """.trimIndent(),
        MediaType.TEXT_MARKDOWN
      )
    }
    println(response.body())
  }
}
