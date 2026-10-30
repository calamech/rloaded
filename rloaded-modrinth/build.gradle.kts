plugins {
    id("rloaded.kotlin-library")
    `java-test-fixtures`
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    // Results carry core types: Outcome, Checksum and DownloadError.
    api(project(":rloaded-core"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(testFixtures(project(":rloaded-core")))
}
