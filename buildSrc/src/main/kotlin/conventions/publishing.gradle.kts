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
