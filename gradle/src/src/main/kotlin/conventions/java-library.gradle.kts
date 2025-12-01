package conventions

import extensions.*

plugins {
  `java-library`
  id("conventions.java")
  id("biz.aQute.bnd.builder")
}

tasks.withType<JavaCompile> {
  options.apply {
    javaModuleVersion = provider { project.version.toString() }

    // Suppress warnings when exporting to modules unresolvable on separate compilation.
    compilerArgs.add("-Xlint:-module")

    release = 11
  }
}

tasks.withType<Javadoc> {
  standardOptions {
    links("https://docs.oracle.com/en/java/javase/$JAVADOC_JDK_VERSION/docs/api/")
    addBooleanOption("Xdoclint:-missing", true)
  }
}

tasks.jar {
  bundle {
    bnd(
      """
      -exportcontents: !*.internal*,*
      Import-Package: !org.checkerframework.*,!com.google.errorprone.*,*
      Bundle-Description: ${project.description}
    """
    )
  }
}
