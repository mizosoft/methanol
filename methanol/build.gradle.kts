import net.ltgt.gradle.errorprone.errorprone
import org.gradle.api.tasks.testing.logging.TestLogEvent
import java.io.PrintWriter
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

plugins {
  id("conventions.java-library")
  id("conventions.static-analysis")
  id("conventions.testing")
  id("conventions.jacoco")
  id("conventions.publishing")
}

dependencies {
  testImplementation(project(":methanol-testing"))
  testImplementation(libs.junit.params)
  testImplementation(libs.mockwebserver)
  testImplementation(libs.jimfs)
}

tasks.test {
  // Don't time out tests when debugging.
  systemProperty("junit.jupiter.execution.timeout.mode", "disabled_on_debug")

  reports {
    junitXml.apply {
      isOutputPerTestCase = true
    }
  }
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
  tckTestImplementation(project(":methanol-testing"))
  tckTestImplementation(libs.testng)
  tckTestImplementation(libs.reactivestreams.tck.flow)
  tckTestImplementation(libs.reactivestreams.examples)
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

  if (System.getenv().containsKey("GITHUB_ACTIONS")) {
    systemProperties["TCK_TIMEOUT_MILLIS"] = 1_000
    systemProperties["TCK_NO_SIGNAL_TIMEOUT_MILLIS"] = 100
  }

  timeout.set(Duration.ofMinutes(5))

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
        val exception = Throwable("multiple test failures")
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
