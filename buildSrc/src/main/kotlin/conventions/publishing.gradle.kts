/*
 * Copyright (c) 2023 Moataz Abdelnasser
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

package conventions

import extensions.artifactId

plugins {
  `java-library`
  `maven-publish`
  signing
}

java {
  withJavadocJar()
  withSourcesJar()
}

publishing {
  publications {
    create<MavenPublication>("maven") {
      artifactId = project.artifactId
      from(components["java"])

      pom {
        name.set(project.name)
        description.set(project.description)
        inceptionYear.set("2019")

        scm {
          url.set("https://github.com/mizosoft/methanol")
          connection.set("scm:git:https://github.com/mizosoft/methanol.git")
          developerConnection.set("scm:git:ssh://git@github.com/mizosoft/methanol.git")
        }

        developers {
          developer {
            id.set("mizosoft")
            name.set("Moataz Abdelnasser")
            email.set("moataz.nasser20@gmail.com")
            url.set("https://github.com/mizosoft")
          }
        }

        licenses {
          license {
            name.set("MIT license");
            url.set("https://opensource.org/licenses/MIT")
          }
        }
      }
    }
  }
}

signing {
  isRequired = false

  val signingKeyId: String? by project
  val signingKey: String? by project
  val signingPassword: String? by project
  useInMemoryPgpKeys(signingKeyId, signingKey, signingPassword)
  sign(publishing.publications["maven"])
}
