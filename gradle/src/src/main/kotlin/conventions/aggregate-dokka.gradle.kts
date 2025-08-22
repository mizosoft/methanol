package conventions

import extensions.*
import org.jetbrains.dokka.gradle.DokkaMultiModuleFileLayout
import org.jetbrains.dokka.gradle.DokkaMultiModuleTask
import org.jetbrains.dokka.gradle.DokkaTaskPartial

plugins {
  id("org.jetbrains.dokka")
}

tasks.withType<DokkaMultiModuleTask> {
  fileLayout = DokkaMultiModuleFileLayout { parent, child ->
    parent.outputDirectory.dir(child.moduleName)
  }
}

dokkaDocumentedProjects.forEach { project ->
  project.tasks.withType<DokkaTaskPartial> {
    dokkaSourceSets.configureEach {
      jdkVersion.set(JAVADOC_JDK_VERSION)
      javadocDocumentedProjects.filter { it -> it != project }
        .forEach { javadocProject ->
          externalDocumentationLink("$JAVADOC_URL/${javadocProject.javaModuleName}/")
        }
    }
  }
}
