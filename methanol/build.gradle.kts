import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.KotlinClosure2
import java.io.PrintWriter
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

plugins {
  id("conventions.java-library")
  id("conventions.java-testing")
  id("conventions.static-analysis")
  id("conventions.coverage")
  id("conventions.publishing")
}

dependencies {
  api(libs.bnd.annotations)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.reactivestreams)
  testImplementation(libs.jimfs)
  testImplementation(libs.mockito)
}

sourceSets {
  create("tckTest") {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
  }
}

val tckTestImplementation by configurations.getting
val tckTestCompileOnly by configurations.getting {
  extendsFrom(configurations.compileOnly.get())
}

dependencies {
  tckTestCompileOnly(libs.checkerframework.qual)
  tckTestImplementation(project(":methanol-testing"))
  tckTestImplementation(libs.testng)
  tckTestImplementation(libs.reactivestreams.tck.flow)
  tckTestImplementation(libs.mockwebserver)
}

tasks.named<JavaCompile>("compileTckTestJava") {
  options.errorprone.isEnabled.set(false)
}

val tckLoggers: ConcurrentHashMap<String, PrintWriter> by extra(ConcurrentHashMap<String, PrintWriter>())
val tckTest by tasks.registering(Test::class) {
  testClassesDirs = sourceSets["tckTest"].output.classesDirs
  classpath = sourceSets["tckTest"].runtimeClasspath

  useTestNG()
  testLogging {
    events = setOf(TestLogEvent.FAILED)
  }

  val runningTckTest = AtomicReference<String>()

  beforeTest(closureOf<TestDescriptor> {
    file("build/test-results/tckTest/logs").mkdirs()

    val logger = tckLoggers.computeIfAbsent(className!!) {
      PrintWriter(
        Files.newBufferedWriter(
          file("build/test-results/tckTest/logs/${className}.log").toPath()
        ), true
      )
    }

    if (runningTckTest.getAndSet(className) != className) {
      println("Running TCK test: ${className!!.split(".").last()}")
    } else {
      logger.println()
    }

    logger.println("Running $displayName")
  })

  afterTest(KotlinClosure2({ descriptor: TestDescriptor, result: TestResult ->
    val logger = tckLoggers[descriptor.className]!!
    logger.println("Result: ${result.resultType}")
    if (result.exceptions.isNotEmpty()) {
      if (result.exceptions.size == 1) {
        result.exceptions.first().printStackTrace(logger)
      } else {
        val exception = Throwable("Multiple test failures")
        result.exceptions.forEach { exception.addSuppressed(it) }
        exception.printStackTrace(logger)
      }
    }

    logger.println()
    logger.println("*".repeat(120))
  }))
}

val closeTckLoggers by tasks.registering {
  doFirst {
    tckLoggers.values.forEach { it.close() }
    tckLoggers.clear()
  }
}

tckTest {
  finalizedBy(closeTckLoggers)
}

tasks.check {
  dependsOn(tckTest)
}

// Extend OSGi configuration to export internal packages that are present as qualified exports in the module-info.java file.
tasks.jar {
  bundle {
    bnd(
      """
      # Export internal packages that have qualified exports in module-info.java.
      # Use x-friends to indicate which bundles should have access (matching JPMS qualified exports).
      Export-Package: \
        com.github.mizosoft.methanol.internal.flow;x-internal:=true;x-friends:="methanol-redis,methanol-testing,methanol-jackson-flux,methanol-jackson3-flux";version=${project.version}, \
        com.github.mizosoft.methanol.internal.extensions;x-internal:=true;x-friends:="methanol-kotlin,methanol-jackson-flux,methanol-jackson3-flux";version=${project.version}, \
        com.github.mizosoft.methanol.internal.cache;x-internal:=true;x-friends:="methanol-redis,methanol-testing";version=${project.version}, \
        com.github.mizosoft.methanol.internal.function;x-internal:=true;x-friends:="methanol-testing,methanol-brotli";version=${project.version}, \
        com.github.mizosoft.methanol.internal;x-internal:=true;x-friends:="methanol-redis,methanol-testing,methanol-jaxb-jakarta,methanol-jaxb,methanol-brotli";version=${project.version}, \
        com.github.mizosoft.methanol.internal.concurrent;x-internal:=true;x-friends:="methanol-jackson,methanol-redis,methanol-testing,methanol-brotli";version=${project.version}
    """
    )
  }
}
