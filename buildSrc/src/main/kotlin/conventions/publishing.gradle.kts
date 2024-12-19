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
        name = project.name
        description = project.description
        inceptionYear = "2019"

        scm {
          url = "https://github.com/mizosoft/methanol"
          connection = "scm:git:https://github.com/mizosoft/methanol.git"
          developerConnection = "scm:git:ssh://git@github.com/mizosoft/methanol.git"
        }

        developers {
          developer {
            id = "mizosoft"
            name = "mizosoft"
            email = "moataz.nasser20@gmail.com"
            url = "https://github.com/mizosoft"
          }
        }

        licenses {
          license {
            name = "MIT license"
            url = "https://opensource.org/licenses/MIT"
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
