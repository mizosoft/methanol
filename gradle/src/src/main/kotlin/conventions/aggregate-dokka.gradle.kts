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

subprojects.filter { it -> it.isIncludedInAggregateDokka }.forEach { kdocProject ->
  kdocProject.tasks.withType<DokkaTaskPartial> {
    dokkaSourceSets.configureEach {
      jdkVersion.set(JAVADOC_JDK_VERSION)
      subprojects.filter { it -> it.isIncludedInAggregateJavadoc && it != kdocProject }
        .forEach { javadocProject ->
          externalDocumentationLink("$JAVADOC_URL/${javadocProject.javaModuleName}/")
        }
    }
  }
}
