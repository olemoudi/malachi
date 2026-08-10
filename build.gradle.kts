plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    jacoco
}

// Aggregated coverage over the app's brain: the pure filtering module, plus the parts of :app
// that JVM unit tests can reach. UI and service code needs a device and stays out of the metric,
// or it would dilute the one number that says whether the filter itself is trustworthy.
val coverageModules = listOf(":core-filter")

tasks.register<JacocoReport>("jacocoAggregatedReport") {
    group = "verification"
    dependsOn(coverageModules.map { "$it:test" } + ":app:testDebugUnitTest")
    val projects = coverageModules.map { project(it) }
    val app = project(":app")
    executionData.setFrom(
        projects.map { it.layout.buildDirectory.file("jacoco/test.exec") } +
            app.layout.buildDirectory.file("outputs/unit_test_code_coverage/debugUnitTest/testDebugUnitTest.exec"),
    )
    sourceDirectories.setFrom(
        projects.map { it.layout.projectDirectory.dir("src/main/kotlin") } +
            app.layout.projectDirectory.dir("src/main/kotlin"),
    )
    classDirectories.setFrom(
        projects.map { it.layout.buildDirectory.dir("classes/kotlin/main") } +
            // Only the classes a JVM test can actually execute; everything Compose- or
            // Android-shaped is unreachable here.
            app.layout.buildDirectory.dir("tmp/kotlin-classes/debug").map { dir ->
                dir.asFileTree.matching {
                    include(
                        "dev/malachi/data/Settings*",
                        "dev/malachi/data/DomainInput*",
                        "dev/malachi/lists/BlocklistCatalog*",
                        "dev/malachi/filter/QueryLog*",
                        "dev/malachi/stats/StatsStore*",
                        "dev/malachi/stats/StatsData*",
                        "dev/malachi/stats/StatsWindow*",
                        "dev/malachi/stats/Counts*",
                        "dev/malachi/stats/DayStats*",
                        "dev/malachi/stats/AppStat*",
                        "dev/malachi/stats/WindowStats*",
                        "dev/malachi/update/UpdateInfo*",
                    )
                }
            },
    )
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(true)
    }
}
