package conventions

val testAggregateReport by tasks.registering(TestReport::class) {
  destinationDirectory = layout.buildDirectory.dir("reports/aggregate-test-results")
}

subprojects {
  testAggregateReport {
    testResults.from(tasks.withType<Test>())
  }
}
