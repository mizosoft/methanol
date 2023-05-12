package extensions

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.tasks.javadoc.Javadoc
import org.gradle.external.javadoc.StandardJavadocDocletOptions

val Javadoc.standardOptions
  get() = options as StandardJavadocDocletOptions

fun Javadoc.standardOptions(block: StandardJavadocDocletOptions.() -> Unit) {
  standardOptions.apply(block)
}

fun Javadoc.classpath(block: ConfigurableFileCollection.() -> Unit) {
  (classpath as ConfigurableFileCollection).block()
}
